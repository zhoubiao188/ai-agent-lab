package com.example.tool.bench;

import java.util.List;
import java.util.Map;

/**
 * 一次对照实验的结果。刻意把"成功"和"降级后成功"分开统计 ——
 * 把降级算进成功率是自欺欺人,线上这么算会漏掉整片坏数据。
 */
public record BenchReport(long seed, int tasks, double latencyScale, long totalMs,
                          List<Row> rows) {

    public record Row(String policy, int success, int degraded, int failed,
                      double successRate, double degradedRate,
                      long avgMs, long p95Ms, int calls, int retries, int repairs,
                      int fallbacks, long circuitRejects,
                      Map<String, Integer> failureKinds) {
    }
}
