package com.example.memory.api;

import com.example.memory.agent.AgentEventSink;
import com.example.memory.agent.AgentOrchestrator;
import com.example.memory.api.dto.ChatRequest;
import com.example.memory.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话入口(POST + SSE)。
 *
 * <p>除了 Day 01 已有的 {@code delta / tool_start / tool_result / message_end},本版本新增:</p>
 * <ul>
 *   <li>{@code memory_compacted} —— 本轮触发了压缩,带上前后 token 与折叠条数;</li>
 *   <li>{@code memory_recall} —— 从归档里召回了相关历史片段;</li>
 *   <li>{@code message_start/message_end.memory} —— 当前水位快照。</li>
 * </ul>
 * <p>把这些"内部动作"外发,前端才能画出记忆水位条 —— 用户看得见"它记得什么",
 * 才会信任"它记住了什么"。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentOrchestrator orchestrator;
    private final AgentProperties props;
    private final ExecutorService streamExecutor;

    public ChatController(AgentOrchestrator orchestrator, AgentProperties props) {
        this.orchestrator = orchestrator;
        this.props = props;
        AtomicInteger seq = new AtomicInteger();
        this.streamExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-stream-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdownNow();
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest req) {
        String conversationId = normalize(req.conversationId());
        String userId = req.userId() == null || req.userId().isBlank() ? "anonymous" : req.userId();

        SseEmitter emitter = new SseEmitter(props.getRequestTimeout().toMillis());
        AgentEventSink sink = new SseEventSink(emitter);
        emitter.onCompletion(() -> log.debug("SSE completed cid={}", conversationId));
        emitter.onTimeout(() -> {
            log.warn("SSE 超时 cid={}", conversationId);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("SSE 异常 cid={}: {}", conversationId, e.toString()));

        streamExecutor.execute(() -> {
            try {
                orchestrator.run(conversationId, userId, req.message(), sink);
            } catch (Exception e) {
                log.error("编排器未捕获异常 cid={}", conversationId, e);
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            }
        });
        return emitter;
    }

    /** 非流式接口:便于 curl / 脚本压测 / 服务间调用 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest req) {
        String conversationId = normalize(req.conversationId());
        StringBuilder answer = new StringBuilder();
        int[] steps = {0};
        Map<String, Object> memory = new LinkedHashMap<>();

        AgentEventSink collect = new AgentEventSink() {
            @Override
            public void emit(String event, Object data) {
                if (!(data instanceof Map<?, ?> map)) {
                    return;
                }
                switch (event) {
                    case "delta" -> {
                        Object text = map.get("text");
                        if (text != null) {
                            answer.append(text);
                        }
                    }
                    case "delta_reset" -> answer.setLength(0);
                    case "message_end" -> {
                        Object content = map.get("content");
                        if (content != null) {
                            answer.setLength(0);
                            answer.append(content);
                        }
                        if (map.get("steps") instanceof Integer s) {
                            steps[0] = s;
                        }
                        if (map.get("memory") instanceof Map<?, ?> m) {
                            memory.clear();
                            m.forEach((k, v) -> memory.put(String.valueOf(k), v));
                        }
                    }
                    default -> {
                        // 非流式接口不关心其余事件
                    }
                }
            }

            @Override
            public boolean closed() {
                return false;
            }
        };

        orchestrator.run(conversationId, req.userId() == null ? "anonymous" : req.userId(),
                req.message(), collect);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "answer", answer.toString(),
                "steps", steps[0],
                "memory", memory));
    }

    private String normalize(String id) {
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    /** SSE 事件桥:写失败即视为客户端断开,后续生成会尽快停止 */
    private static final class SseEventSink implements AgentEventSink {

        private final SseEmitter emitter;
        private volatile boolean closed;

        private SseEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void emit(String event, Object data) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed = true;
            }
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }
}
