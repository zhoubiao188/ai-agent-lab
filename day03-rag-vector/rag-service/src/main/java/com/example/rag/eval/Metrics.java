package com.example.rag.eval;

import com.example.rag.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;

/** 召回指标。全部是纯函数,方便单测用手算数据钉住。 */
public final class Metrics {

    private Metrics() {
    }

    /** 某个 gold 在检索结果里的首次命中名次(1 起);没命中返回 -1。 */
    public static int firstHitRank(List<Chunk> retrieved, Gold gold) {
        for (int i = 0; i < retrieved.size(); i++) {
            Chunk c = retrieved.get(i);
            if (!c.docId().equals(gold.docId())) {
                continue;
            }
            if (gold.anchor() == null || gold.anchor().isBlank() || c.text().contains(gold.anchor())) {
                return i + 1;
            }
        }
        return -1;
    }

    /** recall@k:top-k 里命中的 gold 占全部 gold 的比例。 */
    public static double recallAtK(List<Chunk> retrieved, List<Gold> golds, int k) {
        if (golds.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (Gold g : golds) {
            int rank = firstHitRank(retrieved, g);
            if (rank > 0 && rank <= k) {
                hit++;
            }
        }
        return (double) hit / golds.size();
    }

    /** MRR:第一个正确结果的倒数名次。越接近 1 越好。 */
    public static double mrr(List<Chunk> retrieved, List<Gold> golds) {
        int best = Integer.MAX_VALUE;
        for (Gold g : golds) {
            int rank = firstHitRank(retrieved, g);
            if (rank > 0 && rank < best) {
                best = rank;
            }
        }
        return best == Integer.MAX_VALUE ? 0 : 1.0 / best;
    }

    /**
     * NDCG@k,二值相关性。
     * <p>关键细节:一个 gold 只能被一条结果"消耗"一次。否则当同一标的文档有两块都被召回时,
     * 会被重复计入增益,NDCG 能算出大于 1 的荒谬值 —— 这是自己实现 NDCG 时最常见的错误。</p>
     */
    public static double ndcgAtK(List<Chunk> retrieved, List<Gold> golds, int k) {
        if (golds.isEmpty()) {
            return 0;
        }
        boolean[] consumed = new boolean[golds.size()];
        int depth = Math.min(k, retrieved.size());
        double dcg = 0;
        for (int i = 0; i < depth; i++) {
            int matched = matchUnused(retrieved.get(i), golds, consumed);
            if (matched >= 0) {
                consumed[matched] = true;
                dcg += 1.0 / log2(i + 2);
            }
        }
        double idcg = 0;
        int ideal = Math.min(golds.size(), k);
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg <= 0 ? 0 : dcg / idcg;
    }

    private static int matchUnused(Chunk chunk, List<Gold> golds, boolean[] consumed) {
        for (int i = 0; i < golds.size(); i++) {
            Gold g = golds.get(i);
            if (consumed[i] || !g.docId().equals(chunk.docId())) {
                continue;
            }
            if (g.anchor() == null || g.anchor().isBlank() || chunk.text().contains(g.anchor())) {
                return i;
            }
        }
        return -1;
    }

    static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    /** 百分位延迟。入参是微秒。 */
    public static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }
}
