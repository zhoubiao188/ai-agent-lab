package com.example.rag.search;

import com.example.rag.chunk.Chunk;
import com.example.rag.config.RagProperties;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.index.Scored;
import com.example.rag.index.VectorIndex;
import com.example.rag.rag.Corpus;
import com.example.rag.rerank.FeatureReranker;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索编排:两路召回 → RRF 融合 → 特征重排。
 * <p>这里刻意不用加权分数融合,原因见 {@link #rrf} 的注释。</p>
 */
@Service
public class RetrievalService {

    private final Corpus corpus;
    private final Bm25Index bm25;
    private final VectorIndex vectorIndex;
    private final EmbeddingModel embedding;
    private final FeatureReranker reranker;
    private final RagProperties.Search cfg;

    public RetrievalService(Corpus corpus, Bm25Index bm25, VectorIndex vectorIndex,
                            EmbeddingModel embedding, FeatureReranker reranker, RagProperties props) {
        this.corpus = corpus;
        this.bm25 = bm25;
        this.vectorIndex = vectorIndex;
        this.embedding = embedding;
        this.reranker = reranker;
        this.cfg = props.getSearch();
    }

    public List<ScoredChunk> retrieve(String query, RetrievalMode mode, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK <= 0 ? cfg.getTopK() : topK;

        // 两路都算:一次 BM25 加一次全量点积总量在微秒级,
        // 换来的是调试面板能同时展示四路分数。真实高 QPS 场景应按 mode 只算需要的。
        List<Scored> lexical = bm25.search(query, Math.max(k, cfg.getBm25K()));
        List<Scored> semantic = semanticSearch(query, Math.max(k, cfg.getVectorK()));

        Map<String, Integer> bm25Rank = new LinkedHashMap<>();
        Map<String, Double> bm25Score = new LinkedHashMap<>();
        for (int i = 0; i < lexical.size(); i++) {
            bm25Rank.put(lexical.get(i).chunkId(), i + 1);
            bm25Score.put(lexical.get(i).chunkId(), lexical.get(i).score());
        }
        Map<String, Integer> vectorRank = new LinkedHashMap<>();
        Map<String, Double> vectorScore = new LinkedHashMap<>();
        for (int i = 0; i < semantic.size(); i++) {
            vectorRank.put(semantic.get(i).chunkId(), i + 1);
            vectorScore.put(semantic.get(i).chunkId(), semantic.get(i).score());
        }

        List<Scored> ranked;
        FeatureReranker.QueryContext ctx = null;
        switch (mode) {
            case BM25 -> ranked = lexical;
            case VECTOR -> ranked = semantic;
            case HYBRID -> ranked = rrf(lexical, semantic);
            case FULL -> {
                List<Scored> fused = rrf(lexical, semantic);
                // 查询侧特征只算一次,30 个候选共用;每个候选各算一遍是纯粹的浪费
                ctx = reranker.prepare(query);
                ranked = rerank(ctx, fused, Math.max(k * 3, 12));
            }
            default -> ranked = lexical;
        }

        List<ScoredChunk> out = new ArrayList<>();
        for (int i = 0; i < ranked.size() && i < k; i++) {
            Scored s = ranked.get(i);
            Chunk chunk = corpus.byId(s.chunkId());
            if (chunk == null) {
                continue;
            }
            out.add(new ScoredChunk(chunk, round(s.score()),
                    bm25Rank.get(s.chunkId()), round(bm25Score.get(s.chunkId())),
                    vectorRank.get(s.chunkId()), round(vectorScore.get(s.chunkId())),
                    ctx == null ? null : round(reranker.score(ctx, chunk))));
        }
        return out;
    }

    /** 只做向量召回,供评测与调试复用。 */
    public List<Scored> semanticSearch(String query, int k) {
        float[] q = embedding.embed(query);
        return vectorIndex.search(q, k);
    }

    /**
     * Reciprocal Rank Fusion。
     * <p>BM25 分数无上界,余弦相似度在 0~1,直接加权必须归一化;
     * 而归一化依赖当次结果分布,换个查询尺度就变,线上表现极不稳定。
     * RRF 只用名次:score = Σ weight/(k + rank),对量纲完全不敏感。</p>
     */
    private List<Scored> rrf(List<Scored> lexical, List<Scored> semantic) {
        Map<String, Double> fused = new LinkedHashMap<>();
        for (int i = 0; i < lexical.size(); i++) {
            fused.merge(lexical.get(i).chunkId(), cfg.getBm25Weight() / (cfg.getRrfK() + i + 1), Double::sum);
        }
        for (int i = 0; i < semantic.size(); i++) {
            fused.merge(semantic.get(i).chunkId(), cfg.getVectorWeight() / (cfg.getRrfK() + i + 1), Double::sum);
        }
        List<Scored> out = new ArrayList<>(fused.size());
        fused.forEach((id, score) -> out.add(new Scored(id, score)));
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * 特征重排:最终分 = α·特征分 + (1-α)·归一化后的融合分。
     * <p>保留一部分融合分是有意的 —— 纯特征分会丢掉"两路都排第一"这种一致性信号。</p>
     */
    private List<Scored> rerank(FeatureReranker.QueryContext ctx, List<Scored> fused, int depth) {
        List<Scored> candidates = fused.subList(0, Math.min(depth, fused.size()));
        double max = candidates.stream().mapToDouble(Scored::score).max().orElse(1.0);
        double alpha = cfg.getRerankAlpha();
        List<Scored> rescored = new ArrayList<>(candidates.size());
        for (Scored s : candidates) {
            Chunk chunk = corpus.byId(s.chunkId());
            if (chunk == null) {
                continue;
            }
            double feature = reranker.score(ctx, chunk);
            double fusedNorm = max <= 0 ? 0 : s.score() / max;
            rescored.add(new Scored(s.chunkId(), alpha * feature + (1 - alpha) * fusedNorm));
        }
        rescored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return rescored;
    }

    private Double round(Double v) {
        return v == null ? null : Math.round(v * 10000.0) / 10000.0;
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
