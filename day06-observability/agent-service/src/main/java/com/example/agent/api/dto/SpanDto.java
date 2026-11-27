package com.example.agent.api.dto;

import java.util.List;
import java.util.Map;

/** 单个 Span 的展示模型。startMs 是相对本次运行起点的偏移,前端画瀑布图直接用它。 */
public record SpanDto(
        String id,
        String parentId,
        String name,
        String kind,
        String kindLabel,
        String decisionCode,
        double startMs,
        double durationMs,
        double selfMs,
        String status,
        String errorKind,
        String errorMessage,
        int attempts,
        long tokensIn,
        long tokensOut,
        long costMicros,
        boolean integrity,
        String integrityGrade,
        int redactions,
        Map<String, Object> attrs,
        List<EventDto> events) {

    public record EventDto(String name, long atMs, Map<String, Object> attrs) {
    }
}
