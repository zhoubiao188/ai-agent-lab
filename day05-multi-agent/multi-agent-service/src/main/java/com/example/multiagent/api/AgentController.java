package com.example.multiagent.api;

import com.example.multiagent.agent.AgentEvent;
import com.example.multiagent.agent.EventSink;
import com.example.multiagent.api.dto.CoordRunRequest;
import com.example.multiagent.bench.CoordBench;
import com.example.multiagent.orch.CoordPolicy;
import com.example.multiagent.orch.Coordinator;
import com.example.multiagent.orch.TaskResult;
import com.example.multiagent.plan.PlanFlaw;
import com.example.multiagent.tasks.TaskCatalog;
import com.example.multiagent.tasks.TaskSpec;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时任务接口(SSE)。推的是"计划级"事件:计划生成、缺陷、校验、覆盖、每步执行、复核、重规划。
 *
 * <p>为什么这些事件必须实时推:多 Agent 的失败是<b>过程性</b>的。等任务结束再看结果,
 * 你只会看到一个"没完成",看不到"因为计划把退款排在了报价前面"。</p>
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final Coordinator coordinator;
    private final TaskCatalog catalog;
    private final CoordBench bench;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "coord-run");
        t.setDaemon(true);
        return t;
    });

    public AgentController(Coordinator coordinator, TaskCatalog catalog, CoordBench bench) {
        this.coordinator = coordinator;
        this.catalog = catalog;
        this.bench = bench;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    @PostMapping(value = "/agent/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody CoordRunRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        Object lock = new Object();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        TaskSpec spec = resolve(req);
        CoordPolicy policy = resolvePolicy(req.policy());
        EventSink sink = event -> send(emitter, lock, closed, event);

        pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                TaskResult result = coordinator.run(spec, policy, sink);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", result.taskId());
                data.put("policy", result.policyName());
                data.put("success", result.success());
                data.put("claimed", result.claimed());
                data.put("falseSuccess", result.falseSuccess());
                data.put("reasons", result.reasons());
                data.put("replans", result.replans());
                data.put("steps", result.executedSteps());
                data.put("durationMs", result.durationMs());
                send(emitter, lock, closed, AgentEvent.of("result",
                        System.currentTimeMillis() - t0, data));
                emitter.complete();
            } catch (Exception e) {
                log.warn("任务执行失败 {}", spec.id(), e);
                send(emitter, lock, closed, AgentEvent.of("error",
                        System.currentTimeMillis() - t0, Map.of("message", String.valueOf(e.getMessage()))));
                emitter.complete();
            }
        });
        return emitter;
    }

    private TaskSpec resolve(CoordRunRequest req) {
        TaskSpec base = catalog.require(req.taskId());
        String flaw = req.flaw() == null ? "AUTO" : req.flaw().trim();
        if (flaw.isEmpty() || flaw.equalsIgnoreCase("AUTO")) {
            // 用任务自带的缺陷:页面上默认就是这种,能直接看到"这条错在哪"
            return base;
        }
        if (flaw.equalsIgnoreCase("NONE") || flaw.equalsIgnoreCase("CLEAN")) {
            return new TaskSpec(base.id(), base.request(), base.orderId(), base.sku(), base.quantity(),
                    base.reason(), base.slots(), null);
        }
        return new TaskSpec(base.id(), base.request(), base.orderId(), base.sku(), base.quantity(),
                base.reason(), base.slots(), PlanFlaw.valueOf(flaw));
    }

    private CoordPolicy resolvePolicy(String name) {
        if (name == null || name.isBlank()) {
            return CoordPolicy.FULL;
        }
        return bench.policies().stream()
                .filter(p -> p.name().equals(name) || p.name().startsWith(name))
                .findFirst()
                .orElse(CoordPolicy.FULL);
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
                emitter.send(SseEmitter.event().name(event.type()).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
            }
        }
    }
}
