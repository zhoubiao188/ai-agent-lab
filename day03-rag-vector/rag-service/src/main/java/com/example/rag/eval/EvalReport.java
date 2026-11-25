package com.example.rag.eval;

import java.util.List;
import java.util.Map;

/**
 * 一次完整评测的结果。
 * <p>把它设计成可直接序列化的结构,是为了前端能画出对比表 ——
 * 评测报告如果只能在控制台看,团队成员就不会去看,指标也就不会有人维护。</p>
 */
public record EvalReport(
        String generatedAt,
        String embeddingModel,
        String indexType,
        int docs,
        int chunks,
        int cases,
        long elapsedMs,
        List<StrategyResult> strategies,
        List<Delta> deltas,
        List<CaseRow> details) {

    /** 单个检索策略的整体指标。 */
    public record StrategyResult(
            String mode,
            String label,
            double recall1,
            double recall3,
            double recall5,
            double mrr,
            double ndcg5,
            long p50Micros,
            long p95Micros,
            Map<String, TypeStat> byType) {
    }

    /** 按查询类型切片,用来回答"这个策略究竟救了哪一类查询"。 */
    public record TypeStat(int n, double recall5, double mrr) {
    }

    /** 策略之间的增量,正数代表变好。 */
    public record Delta(String from, String to, double recall5, double mrr, double ndcg5) {
    }

    /** 逐条明细,前端用来定位到底哪几条查询挂了。 */
    public record CaseRow(String id, String type, String query, List<String> golds, Map<String, Integer> ranks) {
    }
}
