package com.example.agent.bench;

import java.util.List;

/**
 * 评测报告。
 *
 * <p>为什么要有"基线":只报"我们的算法 R@3 = 92%"是没有意义的,读者不知道 92% 算好还是算差。
 * 必须同时给出三个最朴素的做法(第一个报错的 Span、最慢的 Span、最贵的 Span)在同一批样本上的成绩,
 * 才能说明"六个信号合起来"到底值不值。</p>
 */
public record BenchReport(
        int incidents,
        long seed,
        String generatedAt,
        List<FamilyRow> families,
        Overall overall,
        List<Baseline> baselines,
        String note) {

    public record FamilyRow(String scenario, String label, int incidents,
                            double recall1, double recall3, double mrr, double avgRank,
                            double avgSpans, double avgDecisions, double avgTopScore, int noCulprit) {
    }

    public record Overall(double recall1, double recall3, double mrr, double avgRank,
                          double avgSpans, double avgDecisions, double avgMs,
                          int silentFailures, double avgRetryAmp) {
    }

    public record Baseline(String name, String description,
                           double recall1, double recall3, double mrr) {
    }
}
