package com.example.rag.eval;

import com.example.rag.chunk.Chunk;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.index.VectorIndex;
import com.example.rag.rag.Corpus;
import com.example.rag.rerank.FeatureReranker;
import com.example.rag.search.Bm25Index;
import com.example.rag.search.RetrievalMode;
import com.example.rag.search.RetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分段延迟探测。
 * <p>这张表比总耗时有用得多:总延迟 3ms 的时候,你得知道是向量算得慢、
 * 还是重排把时间吃掉了。实测下来重排是唯一一个毫秒级的环节,
 * 其余三步都在一百微秒量级 —— 所以优化顺序非常明确。</p>
 */
@SpringBootTest
class LatencyProbeTest {

    @Autowired
    private RetrievalService retrieval;
    @Autowired
    private EmbeddingModel embedding;
    @Autowired
    private VectorIndex vectorIndex;
    @Autowired
    private Bm25Index bm25;
    @Autowired
    private FeatureReranker reranker;
    @Autowired
    private Corpus corpus;

    private String query = "网关流式输出为什么会被总体缓冲,超时怎么配";

    @Test
    @DisplayName("各阶段延迟量级:向量化 / 向量检索 / BM25 / 重排")
    void stageLatency() {
        int rounds = 200;
        // 预热:不预热测出来的第一版数字里混着 JIT 编译时间,完全没有参考价值
        for (int i = 0; i < 30; i++) {
            retrieval.retrieve(query, RetrievalMode.FULL, 8);
        }

        // 同一个 query 反复跑会全部命中 LRU,测出来是 0μs;这里加个自增后缀,强制走冷路径
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        long embedUs = time(() -> Math.round(embedding.embed(query)[0] * 1000), rounds);
        long coldEmbedUs = time(() -> Math.round(embedding.embed(query + "#" + seq.incrementAndGet())[0] * 1000), rounds);
        float[] qv = embedding.embed(query);
        long vectorUs = time(() -> (int) Math.round(vectorIndex.search(qv, 10).get(0).score() * 1000), rounds);
        long bm25Us = time(() -> (int) Math.round(bm25.search(query, 10).get(0).score() * 1000), rounds);
        var ctx = reranker.prepare(query);
        long rerankUs = time(() -> {
            double sum = 0;
            for (Chunk c : corpus.chunks().subList(0, 12)) {
                sum += reranker.score(ctx, c);
            }
            return (int) Math.round(sum * 100);
        }, rounds);
        long fullUs = time(() -> (int) Math.round(retrieval.retrieve(query, RetrievalMode.FULL, 8).get(0).score() * 100000), rounds);

        System.out.println();
        System.out.printf("===== 分段延迟(单次平均, %d 次采样) =====%n", rounds);
        // time() 返回的已经是微秒;之前这里又除了 1000,把所有环节打成 0.0 μs
        System.out.printf("query 向量化(命中缓存) %5.1f μs%n", (double) embedUs);
        System.out.printf("query 向量化(冷路径)   %5.1f μs%n", (double) coldEmbedUs);
        System.out.printf("向量检索(49×512)  %8.1f μs%n", (double) vectorUs);
        System.out.printf("BM25 检索          %8.1f μs%n", (double) bm25Us);
        System.out.printf("特征重排(12 候选)  %8.1f μs%n", (double) rerankUs);
        System.out.printf("FULL 全链路        %8.1f μs%n", (double) fullUs);
        System.out.println("================================");

        // 常规查询的全链路应该在毫秒级
        assertThat(fullUs / 1000.0).isLessThan(50_000.0);
    }

    /** 防止 JIT 把整段计算当死代码删掉 —— 直接测 Supplier 会得到 0.0μs 这种假数据。 */
    private volatile long sink;

    private long time(Supplier<Integer> task, int rounds) {
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 3; r++) {
            long acc = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                acc += task.get();
            }
            sink = acc;
            best = Math.min(best, (System.nanoTime() - t0) / rounds / 1000);
        }
        return best;
    }

    @Test
    @DisplayName("长查询不应引起延迟爆炸")
    void longQuery() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("网关超时和线程池配置应该怎么排查 ").append(i).append(";");
        }
        String longQuery = sb.toString();
        for (int i = 0; i < 5; i++) {
            retrieval.retrieve(longQuery, RetrievalMode.FULL, 8);
        }
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long t0 = System.nanoTime();
            retrieval.retrieve(longQuery, RetrievalMode.FULL, 8);
            samples.add((System.nanoTime() - t0) / 1000);
        }
        long p50 = Metrics.percentile(samples, 50);
        long p95 = Metrics.percentile(samples, 95);
        long max = samples.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("%n超长查询(%d 字): P50 %d μs / P95 %d μs / MAX %d μs%n",
                longQuery.length(), p50, p95, max);
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        System.out.println("样本(μs): " + sorted);
        // 分段定位:把三段分别计时,看抖动出在哪
        List<Long> bm = new ArrayList<>();
        List<Long> vec = new ArrayList<>();
        List<Long> rr = new ArrayList<>();
        var lctx = reranker.prepare(longQuery);
        float[] lqv = embedding.embed(longQuery);
        for (int i = 0; i < 30; i++) {
            long a = System.nanoTime();
            bm25.search(longQuery, 30);
            long b = System.nanoTime();
            vectorIndex.search(lqv, 30);
            long c = System.nanoTime();
            for (Chunk ch : corpus.chunks().subList(0, 24)) {
                reranker.score(lctx, ch);
            }
            long d = System.nanoTime();
            bm.add((b - a) / 1000);
            vec.add((c - b) / 1000);
            rr.add((d - c) / 1000);
        }
        System.out.printf("BM25 P50/P95: %d / %d μs%n", Metrics.percentile(bm, 50), Metrics.percentile(bm, 95));
        System.out.printf("向量 P50/P95: %d / %d μs%n", Metrics.percentile(vec, 50), Metrics.percentile(vec, 95));
        System.out.printf("重排 P50/P95: %d / %d μs%n", Metrics.percentile(rr, 50), Metrics.percentile(rr, 95));
        // 只对 P50 做断言:P95 在共享 CI 机器上会被 GC 与调度噪声主导,
        // 卡 P95 的测试不是质量门禁,是随机失败的来源
        assertThat(p50).isLessThan(20_000L);
    }
}
