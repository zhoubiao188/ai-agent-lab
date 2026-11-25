package com.example.rag.eval;

import com.example.rag.chunk.Chunk;
import com.example.rag.config.RagProperties;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.embed.CachedEmbeddingModel;
import com.example.rag.index.VectorIndex;
import com.example.rag.rag.Corpus;
import com.example.rag.search.RetrievalMode;
import com.example.rag.search.RetrievalService;
import com.example.rag.search.ScoredChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测执行器:同一批 query 跑四种策略,输出可对比的指标。
 *
 * <p>没有评测集的 RAG 调优就是玄学。加了评测集之后,每一次改动
 * (改块长、改 topK、改融合方式)都会立刻有一个数字告诉你方向对不对,
 * 而且能防止"修好一个查询、弄坏另外三个"。</p>
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final List<ModeSpec> MODES = List.of(
            new ModeSpec(RetrievalMode.BM25, "① 关键词 BM25"),
            new ModeSpec(RetrievalMode.VECTOR, "② 向量召回"),
            new ModeSpec(RetrievalMode.HYBRID, "③ 混合 RRF"),
            new ModeSpec(RetrievalMode.FULL, "④ 混合 + 重排")
    );

    private final RetrievalService retrieval;
    private final Corpus corpus;
    private final EmbeddingModel embedding;
    private final VectorIndex vectorIndex;
    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile EvalReport cached;

    public EvalRunner(RetrievalService retrieval, Corpus corpus, EmbeddingModel embedding,
                      VectorIndex vectorIndex, RagProperties props) {
        this.retrieval = retrieval;
        this.corpus = corpus;
        this.embedding = embedding;
        this.vectorIndex = vectorIndex;
        this.props = props;
    }

    private record ModeSpec(RetrievalMode mode, String label) {
    }

    /** 缓存结果,避免前端每刷新一次就把评测重跑一遍。 */
    public EvalReport report(boolean force) {
        EvalReport local = cached;
        if (local != null && !force) {
            return local;
        }
        synchronized (this) {
            if (cached == null || force) {
                cached = run();
            }
            return cached;
        }
    }

    public EvalReport run() {
        List<EvalCase> cases = loadCases();
        int depth = Math.max(props.getEval().getDepth(), 10);
        warmup(cases);
        long t0 = System.currentTimeMillis();

        List<EvalReport.StrategyResult> strategies = new ArrayList<>();
        // caseId → mode → rank
        Map<String, Map<String, Integer>> ranksByCase = new LinkedHashMap<>();

        for (ModeSpec spec : MODES) {
            List<Long> latencies = new ArrayList<>(cases.size());
            double sumRecall1 = 0;
            double sumRecall3 = 0;
            double sumRecall5 = 0;
            double sumMrr = 0;
            double sumNdcg = 0;
            Map<String, List<double[]>> byType = new LinkedHashMap<>();

            for (EvalCase c : cases) {
                long s = System.nanoTime();
                List<Chunk> retrieved = retrieve(c.query(), spec.mode(), depth);
                latencies.add((System.nanoTime() - s) / 1000);

                double r1 = Metrics.recallAtK(retrieved, c.golds(), 1);
                double r3 = Metrics.recallAtK(retrieved, c.golds(), 3);
                double r5 = Metrics.recallAtK(retrieved, c.golds(), 5);
                double mrr = Metrics.mrr(retrieved, c.golds());
                double ndcg = Metrics.ndcgAtK(retrieved, c.golds(), 5);

                sumRecall1 += r1;
                sumRecall3 += r3;
                sumRecall5 += r5;
                sumMrr += mrr;
                sumNdcg += ndcg;

                byType.computeIfAbsent(c.type(), k -> new ArrayList<>()).add(new double[]{r5, mrr});

                int firstRank = Integer.MAX_VALUE;
                for (Gold g : c.golds()) {
                    int rank = Metrics.firstHitRank(retrieved, g);
                    if (rank > 0 && rank < firstRank) {
                        firstRank = rank;
                    }
                }
                ranksByCase.computeIfAbsent(c.id(), k -> new LinkedHashMap<>())
                        .put(spec.mode().name(), firstRank == Integer.MAX_VALUE ? -1 : firstRank);
            }

            int n = cases.size();
            Map<String, EvalReport.TypeStat> typeStats = new LinkedHashMap<>();
            byType.forEach((type, values) -> {
                double r5 = values.stream().mapToDouble(v -> v[0]).average().orElse(0);
                double mrr = values.stream().mapToDouble(v -> v[1]).average().orElse(0);
                typeStats.put(type, new EvalReport.TypeStat(values.size(), round(r5), round(mrr)));
            });

            strategies.add(new EvalReport.StrategyResult(
                    spec.mode().name(), spec.label(),
                    round(sumRecall1 / n), round(sumRecall3 / n), round(sumRecall5 / n),
                    round(sumMrr / n), round(sumNdcg / n),
                    Metrics.percentile(latencies, 50), Metrics.percentile(latencies, 95),
                    typeStats));
        }

        List<EvalReport.Delta> deltas = new ArrayList<>();
        for (int i = 1; i < strategies.size(); i++) {
            EvalReport.StrategyResult a = strategies.get(i - 1);
            EvalReport.StrategyResult b = strategies.get(i);
            deltas.add(new EvalReport.Delta(a.mode(), b.mode(),
                    round(b.recall5() - a.recall5()),
                    round(b.mrr() - a.mrr()),
                    round(b.ndcg5() - a.ndcg5())));
        }

        List<EvalReport.CaseRow> details = new ArrayList<>();
        for (EvalCase c : cases) {
            List<String> golds = c.golds().stream().map(g -> g.docId() + " :: " + g.anchor()).toList();
            details.add(new EvalReport.CaseRow(c.id(), c.type(), c.query(), golds,
                    ranksByCase.getOrDefault(c.id(), Map.of())));
        }

        String embedName = embedding.name();
        if (embedding instanceof CachedEmbeddingModel cachedModel) {
            log.debug("向量缓存命中 {}/{}", cachedModel.hitCount(), cachedModel.hitCount() + cachedModel.missCount());
        }

        EvalReport report = new EvalReport(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                embedName, vectorIndex.type(), corpus.docs().size(), corpus.chunks().size(),
                cases.size(), System.currentTimeMillis() - t0, strategies, deltas, details);
        log.info("评测完成: {} 条样本 / {}ms", cases.size(), report.elapsedMs());
        return report;
    }

    private List<Chunk> retrieve(String query, RetrievalMode mode, int depth) {
        List<ScoredChunk> hits = retrieval.retrieve(query, mode, depth);
        List<Chunk> out = new ArrayList<>(hits.size());
        hits.forEach(h -> out.add(h.chunk()));
        return out;
    }

    /**
     * 预热一轮。
     * <p>第一版评测里 BM25 的 P95 报了 38ms,而向量只有 93μs —— 不是算法慢,
     * 是 BM25 恰好第一个跑,把 JIT 编译时间也算进了自己的耗时。
     * 不预热的延迟数字基本没有参考价值。</p>
     */
    private void warmup(List<EvalCase> cases) {
        for (int round = 0; round < 2; round++) {
            for (EvalCase c : cases) {
                retrieval.retrieve(c.query(), RetrievalMode.FULL, 10);
            }
        }
    }

    public List<EvalCase> loadCases() {
        String path = props.getEval().getQueries();
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            List<EvalCase> cases = mapper.readValue(in, new TypeReference<List<EvalCase>>() {
            });
            if (cases.isEmpty()) {
                throw new IllegalStateException("评测集为空: " + path);
            }
            return cases;
        } catch (Exception e) {
            throw new IllegalStateException("加载评测集失败: " + path, e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
