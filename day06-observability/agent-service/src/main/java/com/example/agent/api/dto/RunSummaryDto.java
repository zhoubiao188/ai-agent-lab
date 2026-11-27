package com.example.agent.api.dto;

/** 列表页用的一行。够判断"要不要点进去",不塞 Span。 */
public record RunSummaryDto(
        String traceId,
        String taskId,
        String scenario,
        String scenarioLabel,
        String outcome,
        String outcomeLabel,
        long startedAt,
        double durationMs,
        double trust,
        int spanCount,
        long tokens,
        long costMicros,
        double retryAmplification,
        boolean silent,
        String topSuspect,
        String topSuspectKind) {
}
