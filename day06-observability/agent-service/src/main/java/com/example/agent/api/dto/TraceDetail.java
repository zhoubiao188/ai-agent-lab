package com.example.agent.api.dto;

import java.util.List;

public record TraceDetail(
        String traceId,
        String taskId,
        String scenario,
        String scenarioLabel,
        String question,
        String userId,
        long startedAt,
        String outcome,
        String outcomeLabel,
        double trust,
        String answer,
        double durationMs,
        long tokens,
        long costMicros,
        int spanCount,
        int decisionCount,
        double retryAmplification,
        boolean sampled,
        String narrative,
        List<SpanDto> spans,
        List<DecisionDto> decisions,
        List<SuspectDto> suspects) {
}
