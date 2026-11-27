package com.example.o11y.export;

import com.example.o11y.span.Span;
import com.example.o11y.span.SpanEvent;
import com.example.o11y.span.Trace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONL 导出:一行一个 Span。
 *
 * <p>为什么不是一个大 JSON:排障时最常见的操作是 {@code grep} 和 {@code jq}。
 * 一行一个 Span 才能 {@code grep '"name":"refund.commit"'},才能直接喂给 ClickHouse 的
 * JSONEachRow 导入。嵌套结构好看,但不好查。</p>
 */
public final class JsonlExporter {

    private final ObjectMapper mapper = new ObjectMapper();

    public String export(Trace trace) {
        long traceStart = trace.root().startNanos();
        StringBuilder sb = new StringBuilder(4096);
        for (Span span : trace.spans()) {
            sb.append(line(trace, span, traceStart)).append('\n');
        }
        for (var decision : trace.decisions()) {
            sb.append(decisionLine(trace, decision)).append('\n');
        }
        return sb.toString();
    }

    private String line(Trace trace, Span span, long traceStart) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", trace.traceId());
        row.put("spanId", span.spanId());
        row.put("parentId", span.parentId());
        row.put("name", span.name());
        row.put("kind", span.kind().name());
        row.put("decisionCode", span.decisionCode());
        row.put("startMs", round3(span.startNanos() - traceStart, 1e6));
        row.put("durationMs", round3(span.durationNanos(), 1e6));
        row.put("selfMs", round3(span.selfNanos(), 1e6));
        row.put("status", span.status().name());
        row.put("errorKind", span.errorKind());
        row.put("attempts", span.attempts());
        row.put("integrity", span.integrityOk());
        row.put("integrity.grade", span.integrityGrade().name());
        row.put("tokens", Map.of("in", span.tokensIn(), "out", span.tokensOut()));
        row.put("costMicros", span.costMicros());
        row.put("attrs", span.attrs());
        if (!span.events().isEmpty()) {
            List<Map<String, Object>> events = new ArrayList<>();
            for (SpanEvent e : span.events()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("name", e.name());
                ev.put("atMs", e.epochMilli() - trace.startedEpochMilli());
                ev.putAll(e.attrs());
                events.add(ev);
            }
            row.put("events", events);
        }
        row.put("outcome", trace.outcome().name());
        return write(row);
    }

    private String decisionLine(Trace trace, com.example.o11y.decision.Decision d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", trace.traceId());
        row.put("type", "decision");
        row.put("seq", d.seq());
        row.put("code", d.code());
        row.put("title", d.point().title());
        row.put("spanId", d.spanId());
        row.put("chosen", d.chosen());
        row.put("basis", d.basis().name());
        row.put("confidence", d.confidence());
        row.put("margin", round3(d.margin(), 1.0));
        row.put("alternatives", d.alternatives().stream()
                .map(a -> Map.of("name", a.name(), "score", a.score(), "reason", a.reason()))
                .toList());
        row.put("attrs", d.attrs());
        return write(row);
    }

    private String write(Map<String, Object> row) {
        try {
            return mapper.writeValueAsString(row);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + e.getOriginalMessage() + "\"}";
        }
    }

    private static double round3(double value, double divisor) {
        return Math.round(value / divisor * 1000.0) / 1000.0;
    }
}
