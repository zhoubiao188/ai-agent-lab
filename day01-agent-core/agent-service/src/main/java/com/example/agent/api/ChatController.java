package com.example.agent.api;

import com.example.agent.agent.AgentEventSink;
import com.example.agent.agent.AgentOrchestrator;
import com.example.agent.agent.ConversationMemory;
import com.example.agent.api.dto.ChatRequest;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.dto.Msg;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话入口。
 * <p>为什么用 SSE 而不是 WebSocket:Agent 的输出天然是"一问一答、单向下行",
 * SSE 走普通 HTTP,天然兼容网关/Nginx/鉴权中间件,断线还能靠 EventSource 语义重连;
 * WebSocket 需要额外处理心跳、粘性会话、网关升级,收益不明显。</p>
 *
 * <p>注意:这里用的是 <b>POST + SSE</b>。浏览器原生 {@code EventSource} 只支持 GET,
 * 因此前端改用 {@code fetch + ReadableStream} 手动解析 SSE 帧(见 web-vue/src/api/agent.ts)。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentOrchestrator orchestrator;
    private final ConversationMemory memory;
    private final AgentProperties props;
    private final ExecutorService streamExecutor;

    public ChatController(AgentOrchestrator orchestrator, ConversationMemory memory, AgentProperties props) {
        this.orchestrator = orchestrator;
        this.memory = memory;
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
        String conversationId = req.conversationId() == null || req.conversationId().isBlank()
                ? UUID.randomUUID().toString() : req.conversationId();
        String userId = req.userId() == null ? "anonymous" : req.userId();

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

    /** 非流式接口,便于脚本/服务间调用与 curl 调试 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest req) {
        String conversationId = req.conversationId() == null || req.conversationId().isBlank()
                ? UUID.randomUUID().toString() : req.conversationId();
        StringBuilder answer = new StringBuilder();
        int[] steps = {0};
        AgentEventSink collect = new AgentEventSink() {
            @Override
            public void emit(String event, Object data) {
                if ("delta".equals(event) && data instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null) {
                        answer.append(text);
                    }
                } else if ("delta_reset".equals(event)) {
                    answer.setLength(0);
                } else if ("message_end".equals(event) && data instanceof Map<?, ?> map) {
                    Object content = map.get("content");
                    if (content != null) {
                        answer.setLength(0);
                        answer.append(content);
                    }
                    Object s = map.get("steps");
                    if (s instanceof Integer i) {
                        steps[0] = i;
                    }
                }
            }

            @Override
            public boolean closed() {
                return false;
            }
        };
        orchestrator.run(conversationId, req.userId() == null ? "anonymous" : req.userId(), req.message(), collect);
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "answer", answer.toString(),
                "steps", steps[0]));
    }

    @GetMapping("/{conversationId}/history")
    public List<Msg> history(@PathVariable String conversationId) {
        return memory.history(conversationId);
    }

    @DeleteMapping("/{conversationId}")
    public Map<String, Object> clear(@PathVariable String conversationId) {
        memory.clear(conversationId);
        return Map.of("conversationId", conversationId, "cleared", true);
    }

    /** SSE 事件桥:把编排器的事件写进 SseEmitter,写失败即视为客户端断开。 */
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
