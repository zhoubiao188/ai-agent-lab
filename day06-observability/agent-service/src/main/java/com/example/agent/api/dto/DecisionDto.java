package com.example.agent.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 决策台账的展示模型。
 *
 * <p>{@code question} 与 {@code symptom} 直接来自枚举,不需要前端再维护一份文案 ——
 * 决策点加一个,页面自动多一行,这是把知识放在代码里的好处。</p>
 */
public record DecisionDto(
        int seq,
        String code,
        String title,
        String question,
        String symptom,
        String spanId,
        String chosen,
        String basis,
        String basisLabel,
        double confidence,
        double margin,
        double latencyMs,
        List<AlternativeDto> alternatives,
        Map<String, Object> attrs) {

    public record AlternativeDto(String name, double score, String reason) {
    }
}
