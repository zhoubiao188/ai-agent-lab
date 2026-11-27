package com.example.o11y.triage;

import com.example.o11y.span.SpanKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个"嫌疑"Span:分数 + 分数构成 + 人话证据。
 *
 * <p>只给分数是没有用的。"这条 0.83 分"没法让值班的人做决定,必须同时给
 * "这 0.83 是怎么来的":耗时占多少、错误占多少、重试占多少。分数构成才是可解释性。</p>
 */
public final class Suspect {

    private final String spanId;
    private final String name;
    private final SpanKind kind;
    private final String decisionCode;
    private final double score;
    private final Map<String, Double> contributions;
    private final List<String> evidence;

    public Suspect(String spanId, String name, SpanKind kind, String decisionCode, double score,
                   Map<String, Double> contributions, List<String> evidence) {
        this.spanId = spanId;
        this.name = name;
        this.kind = kind;
        this.decisionCode = decisionCode;
        this.score = score;
        this.contributions = new LinkedHashMap<>(contributions);
        this.evidence = List.copyOf(evidence);
    }

    public String spanId() {
        return spanId;
    }

    public String name() {
        return name;
    }

    public SpanKind kind() {
        return kind;
    }

    public String decisionCode() {
        return decisionCode;
    }

    public double score() {
        return score;
    }

    public Map<String, Double> contributions() {
        return Collections.unmodifiableMap(contributions);
    }

    public List<String> evidence() {
        return evidence;
    }

    @Override
    public String toString() {
        return String.format("%s(%s) %.3f %s", name, kind, score, evidence);
    }
}
