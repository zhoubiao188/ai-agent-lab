package com.example.rag.api;

import com.example.rag.api.dto.Citation;
import com.example.rag.api.dto.RetrieveRequest;
import com.example.rag.chunk.Chunk;
import com.example.rag.config.RagProperties;
import com.example.rag.llm.LlmClient;
import com.example.rag.search.RetrievalMode;
import com.example.rag.search.RetrievalService;
import com.example.rag.search.ScoredChunk;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 问答接口(SSE 流式)。
 *
 * <p>事件顺序: citations(先给引用)→ delta(逐帧正文)→ done(收尾)。
 * 先推 citations 而不是等答案生成完再推,是因为前端可以立刻渲染"正在查阅的 3 篇文档",
 * 把等待时间的感觉砍掉一半。</p>
 *
 * <p>坑点一:客户端断开后继续 send 会抛 IOException,必须及时停止生成,
 * 否则模型会白跑完整个回答,占着线程与 token。</p>
 * <p>坑点二:SseEmitter 的回调在容器线程里执行,不能做重活。</p>
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final RetrievalService retrieval;
    private final LlmClient llm;
    private final RagProperties props;
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "rag-ask");
        t.setDaemon(true);
        return t;
    });

    public AskController(RetrievalService retrieval, LlmClient llm, RagProperties props) {
        this.retrieval = retrieval;
        this.llm = llm;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@Valid @RequestBody RetrieveRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        RetrievalMode mode = RetrievalMode.from(req.mode());
        int topK = req.topK() == null ? props.getLlm().getContextTopK() : req.topK();

        executor.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<ScoredChunk> hits = retrieval.retrieve(req.query(), mode, topK);
                List<Chunk> contexts = new ArrayList<>();
                List<Citation> citations = new ArrayList<>();
                int idx = 1;
                for (ScoredChunk hit : hits) {
                    if (contexts.size() >= props.getLlm().getContextTopK()) {
                        break;
                    }
                    Chunk c = hit.chunk();
                    contexts.add(c);
                    citations.add(new Citation(idx++, c.id(), c.docTitle(), c.section(),
                            Math.round(hit.score() * 10000) / 10000.0, snippet(c.text())));
                }
                long retrieveMs = System.currentTimeMillis() - t0;
                send(emitter, closed, "citations", citations);

                StringBuilder answer = new StringBuilder();
                llm.streamAnswer(req.query(), contexts, delta -> {
                    if (closed.get()) {
                        return;
                    }
                    answer.append(delta);
                    send(emitter, closed, "delta", Map.of("text", delta));
                });

                send(emitter, closed, "done", Map.of(
                        "answer", answer.toString(),
                        "retrieveMs", retrieveMs,
                        "totalMs", System.currentTimeMillis() - t0,
                        "citations", citations.size(),
                        "model", llm.name()));
                emitter.complete();
            } catch (UncheckedIOException disconnect) {
                // 客户端主动断开,直接收尾,不再生成剩余内容
                log.debug("SSE 客户端断开,停止生成");
                emitter.complete();
            } catch (Exception e) {
                log.warn("问答失败: {}", e.toString());
                try {
                    send(emitter, closed, "error", Map.of("message", String.valueOf(e.getMessage())));
                } catch (RuntimeException ignore) {
                    // 连接已断,无需再报错
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端已经走了,标记关闭,后续不再尝试发送
            closed.set(true);
            throw new UncheckedIOException(new IOException("SSE 客户端已断开", e));
        }
    }

    private String snippet(String text) {
        String s = text.replace('\n', ' ').trim();
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }
}
