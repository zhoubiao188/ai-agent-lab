package com.example.rag.api;

import com.example.rag.api.dto.KbStats;
import com.example.rag.chunk.Chunk;
import com.example.rag.config.RagProperties;
import com.example.rag.embed.CachedEmbeddingModel;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.index.VectorIndex;
import com.example.rag.llm.LlmClient;
import com.example.rag.rag.Corpus;
import com.example.rag.search.Bm25Index;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识库只读接口:概况、文档列表、块明细。 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final Corpus corpus;
    private final Bm25Index bm25;
    private final VectorIndex vectorIndex;
    private final EmbeddingModel embedding;
    private final LlmClient llm;
    private final RagProperties props;

    public KbController(Corpus corpus, Bm25Index bm25, VectorIndex vectorIndex,
                        EmbeddingModel embedding, LlmClient llm, RagProperties props) {
        this.corpus = corpus;
        this.bm25 = bm25;
        this.vectorIndex = vectorIndex;
        this.embedding = embedding;
        this.llm = llm;
        this.props = props;
    }

    @GetMapping("/stats")
    public KbStats stats() {
        Map<String, String> titles = new LinkedHashMap<>();
        corpus.docs().forEach(d -> titles.put(d.id(), d.title()));
        double hitRate = 0;
        if (embedding instanceof CachedEmbeddingModel cached) {
            long total = cached.hitCount() + cached.missCount();
            hitRate = total == 0 ? 0 : (double) cached.hitCount() / total;
        }
        return KbStats.of(corpus.docs().size(), corpus.chunks().size(), bm25.vocabSize(),
                Math.round(bm25.avgDocLen() * 10) / 10.0, embedding.dim(), embedding.name(),
                vectorIndex.type(), corpus.buildMillis(), corpus.embedMillis(),
                Math.round(hitRate * 10000) / 10000.0, llm.name(),
                corpus.docChunkCount(), titles);
    }

    @GetMapping("/chunks")
    public List<Chunk> chunks(@RequestParam(required = false) String docId,
                              @RequestParam(defaultValue = "20") int limit) {
        return corpus.chunks().stream()
                .filter(c -> docId == null || docId.isBlank() || c.docId().equals(docId))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 暴露当前生效的检索参数,便于前端在调试面板上标注"这次用的是哪套配置"。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkMaxChars", props.getChunk().getMaxChars());
        m.put("chunkOverlapChars", props.getChunk().getOverlapChars());
        m.put("bm25K", props.getSearch().getBm25K());
        m.put("vectorK", props.getSearch().getVectorK());
        m.put("rrfK", props.getSearch().getRrfK());
        m.put("rerankAlpha", props.getSearch().getRerankAlpha());
        m.put("contextTopK", props.getLlm().getContextTopK());
        m.put("embedProvider", props.getEmbed().getProvider());
        m.put("llmProvider", props.getLlm().getProvider());
        return m;
    }
}
