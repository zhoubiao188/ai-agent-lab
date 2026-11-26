package com.example.tool.api;

import com.example.tool.agent.AgentEvent;
import com.example.tool.agent.AgentOrchestrator;
import com.example.tool.agent.EventSink;
import com.example.tool.agent.TaskOutcome;
import com.example.tool.api.dto.AgentRunRequest;
import com.example.tool.exec.RunPolicy;
import com.example.tool.fault.FaultInjector;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时任务接口(SSE)。每发生一件事就推一条:计划、调用开始、重试、修复、熔断、降级、答案。
 *
 * <p>这里有两个必须处理的细节:</p>
 * <p>1、<b>多线程发事件要加锁</b>。工具是并行跑的,{@code SseEmitter.send} 不是线程安全的,
 * 不加锁在并发高时会抛 IllegalStateException,而且报错位置跟真实原因毫无关系。</p>
 * <p>2、<b>客户端断开后要继续把任务跑完吗?</b> 不跑。写操作已经发出去了没法撤销,
 * 但剩下没发的调用应该停 —— 所以这里断开会把 closed 置位,后续 send 直接丢弃。</p>
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final FaultInjector faults;
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-run");
        t.setDaemon(true);
        return t;
    });

    public AgentController(AgentOrchestrator orchestrator, FaultInjector faults) {
        this.orchestrator = orchestrator;
        this.faults = faults;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    @PostMapping(value = "/agent/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody AgentRunRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        Object lock = new Object();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        String requestId = "run" + seq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        RunPolicy policy = req.runPolicy();
        String task = req.taskOrDefault();
        long seed = req.seed() == null ? System.currentTimeMillis() : req.seed();
        boolean faultOn = req.faults() == null || req.faults();

        EventSink sink = event -> send(emitter, lock, closed, event);

        pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                faults.configure(seed, faultOn);
                // 调用 ID 由 seed 派生,而不是用自增的 requestId:
                // 这样同一个 seed + 同一个任务,每次跑都遇到完全相同的故障序列,演示和排障都能复现
                String taskKey = "s" + Long.toHexString(seed);
                TaskOutcome outcome = orchestrator.run(task, requestId, taskKey, policy, sink);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", outcome.status().name());
                data.put("answer", outcome.answer());
                data.put("wallMs", outcome.wallMs());
                data.put("calls", outcome.callCount());
                data.put("retries", outcome.retries());
                data.put("repairs", outcome.repairs());
                data.put("fallbacks", outcome.fallbacks());
                send(emitter, lock, closed, AgentEvent.of("result",
                        System.currentTimeMillis() - t0, data));
                emitter.complete();
            } catch (Exception e) {
                log.warn("任务执行失败 {}", requestId, e);
                send(emitter, lock, closed, AgentEvent.of("error",
                        System.currentTimeMillis() - t0,
                        Map.of("message", String.valueOf(e.getMessage()))));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, Object lock, AtomicBoolean closed, AgentEvent event) {
        if (closed.get()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("t", event.t());
        data.putAll(event.data());
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event.type())
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
            }
        }
    }
}
