package com.example.agent.api.dto;

import java.util.List;
import java.util.Map;

public record SuspectDto(
        String spanId,
        String name,
        String kind,
        String kindLabel,
        String decisionCode,
        double score,
        Map<String, Double> contributions,
        List<String> evidence) {
}
