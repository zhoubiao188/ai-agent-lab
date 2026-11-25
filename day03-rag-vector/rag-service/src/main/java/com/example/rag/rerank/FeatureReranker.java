package com.example.rag.rerank;

import com.example.rag.chunk.Chunk;
import com.example.rag.search.Bm25Index;
import com.example.rag.text.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特征重排:用五个可解释的特征给 (query, chunk) 打分,替代 Cross-Encoder。
 *
 * <p>为什么不用 Cross-Encoder:它需要额外的模型推理,复杂度 O(n) 且延迟在几十毫秒到几百毫秒,
 * 调优时你又只能看到"分数变了"而不知道原因。特征重排是纯 CPU 计算,微秒级,
 * 每一项特征都能单独打开看,线上出问题能定位到具体是哪一项把正确块压下去了。</p>
 *
 * <p>这也是一个残酷的事实:RAG 效果的大头在召回与提示词,重排是锦上添花。
 * 先保证 recall@5,再谈重排。</p>
 */
@Component
public class FeatureReranker {

    private final Bm25Index bm25;
    /** 块级特征预计算缓存。重排每轮要对同一批块算特征,不缓存的话分词开销占满整个延迟。 */
    private final Map<String, Prepared> prepared = new ConcurrentHashMap<>();

    /**
     * 参与重排的查询词上限。
     * <p>重排的代价是 O(查询词数 × 块长 × 候选数)。一段 590 字的超长查询会产生近 600 个词,
     * 实测能把一次检索从 1ms 拖到 546ms。按 IDF 取前 24 个词,信息量几乎不损失 ——
     * 因为长查询里绝大多数词都是重复或低信息量的。</p>
     */
    private static final int MAX_QUERY_TOKENS = 24;

    public FeatureReranker(Bm25Index bm25) {
        this.bm25 = bm25;
    }

    /** 一次请求只算一次查询侧特征,候选之间复用。 */
    public record QueryContext(String raw, List<String> tokens) {
    }

    public QueryContext prepare(String query) {
        List<String> distinct = Tokenizer.distinct(query);
        if (distinct.size() > MAX_QUERY_TOKENS) {
            distinct = distinct.stream()
                    .sorted(Comparator.comparingDouble(bm25::idf).reversed())
                    .limit(MAX_QUERY_TOKENS)
                    .toList();
        }
        return new QueryContext(query, distinct);
    }

    /** 预先算好、之后反复使用的块级特征。 */
    private record Prepared(Set<String> tokens, String flat, Set<String> sectionTokens, String raw) {
    }

    private Prepared prepare(Chunk chunk) {
        List<String> tokens = Tokenizer.tokenize(chunk.embedText());
        return new Prepared(
                new HashSet<>(tokens),
                String.join(" ", tokens),
                new HashSet<>(Tokenizer.tokenize(chunk.section() == null ? "" : chunk.section())),
                chunk.text());
    }

    public double score(String query, Chunk chunk) {
        return score(prepare(query), chunk);
    }

    /** 综合特征分,范围 0~1。 */
    public double score(QueryContext q, Chunk chunk) {
        List<String> qTokens = q.tokens();
        if (qTokens.isEmpty()) {
            return 0;
        }
        Prepared p = prepared.computeIfAbsent(chunk.id(), k -> prepare(chunk));

        // 1、IDF 加权覆盖率:查询里的词有多少被块覆盖,稀缺词权重更高
        double totalIdf = 0;
        double coveredIdf = 0;
        for (String t : qTokens) {
            double idf = bm25.idf(t);
            totalIdf += idf;
            if (p.tokens().contains(t)) {
                coveredIdf += idf;
            }
        }
        double coverage = totalIdf <= 0 ? 0 : coveredIdf / totalIdf;

        // 2、连续短语命中:查询里相邻的两个词在块中也相邻,信号强度远高于分散命中
        double phrase = phraseHit(qTokens, p.flat()) ? 1.0 : 0.0;

        // 3、小节标题命中:标题命中通常意味着整块都在讲这件事
        double titleHit = sectionHit(qTokens, p.sectionTokens()) ? 1.0 : 0.0;

        // 4、块长偏好:太短缺上下文,太长主题被平均,取中间区间最舒服
        double len = chunk.text().length();
        double lenScore = len < 200 ? len / 200.0 : (len <= 620 ? 1.0 : Math.max(0.3, 620.0 / len));

        // 5、命中位置:越靠前越可能是这一块的主题
        double posScore = positionScore(qTokens, p.raw());

        return 0.42 * coverage + 0.20 * phrase + 0.12 * titleHit + 0.10 * lenScore + 0.16 * posScore;
    }

    private boolean phraseHit(List<String> qTokens, String flat) {
        if (qTokens.size() < 2) {
            return false;
        }
        for (int i = 0; i + 1 < qTokens.size(); i++) {
            if (flat.contains(qTokens.get(i) + " " + qTokens.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private boolean sectionHit(List<String> qTokens, Set<String> sectionTokens) {
        for (String t : qTokens) {
            if (sectionTokens.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private double positionScore(List<String> qTokens, String text) {
        int first = Integer.MAX_VALUE;
        for (String t : qTokens) {
            int idx = text.indexOf(t);
            if (idx >= 0 && idx < first) {
                first = idx;
            }
        }
        if (first == Integer.MAX_VALUE) {
            return 0;
        }
        // 命中落在前 30% 视为理想位置,线性衰减到 0
        double ratio = (double) first / Math.max(1, text.length());
        return Math.max(0.0, 1.0 - ratio / 0.5);
    }
}
