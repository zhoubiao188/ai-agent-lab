package com.example.agent.api;

import com.example.agent.api.dto.MetaDtos;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.store.TraceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合指标。数据来自内存里的 trace,线上应该接 Prometheus / ClickHouse。
 *
 * <p>这里刻意保留了一个"慢但诚实"的实现:每次请求都遍历所有 trace 现算。
 * 因为我想让读者看清每个指标的定义 —— 换成预聚合之后,定义就被埋进 pipeline 里了,
 * 而指标定义恰恰是最容易算错的部分(比如 successRate 到底算不算沉默失败)。</p>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final TraceStore store;

    public MetricsController(TraceStore store) {
        this.store = store;
    }

    @GetMapping("/overview")
    public MetaDtos.OverviewDto overview() {
        List<Trace> traces = store.all();
        if (traces.isEmpty()) {
            return new MetaDtos.OverviewDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        long success = traces.stream()
                .filter(t -> t.outcome() == com.example.o11y.span.Outcome.SUCCESS).count();
        long silent = traces.stream()
                .filter(t -> t.outcome() == com.example.o11y.span.Outcome.SUCCESS
                        && t.spans().stream().anyMatch(s -> !s.integrityOk())).count();

        List<Double> durations = traces.stream().map(Trace::totalMs).sorted().toList();
        double p50 = percentile(durations, 0.5);
        double p95 = percentile(durations, 0.95);
        double avgTokens = traces.stream().mapToLong(Trace::totalTokens).average().orElse(0);
        double costPerSuccess = success == 0 ? 0
                : traces.stream().mapToLong(Trace::totalCostMicros).sum() / (double) success;
        double avgRetry = traces.stream().mapToDouble(Trace::retryAmplification).average().orElse(0);
        double avgSpans = traces.stream().mapToInt(t -> t.spans().size()).average().orElse(0);
        double avgDecisions = traces.stream().mapToInt(t -> t.decisions().size()).average().orElse(0);

        Map<String, int[]> toolStats = new LinkedHashMap<>();
        for (Trace trace : traces) {
            for (Span span : trace.spans()) {
                if (span.kind() != SpanKind.TOOL && span.kind() != SpanKind.WRITE) {
                    continue;
                }
                String name = span.name().replaceFirst("^(tool|write) ", "");
                int[] stat = toolStats.computeIfAbsent(name, k -> new int[3]);
                stat[0]++;
                if (span.status() == SpanStatus.ERROR) {
                    stat[1]++;
                }
                stat[2] += span.attempts();
            }
        }
        List<MetaDtos.ToolStatDto> tools = new ArrayList<>();
        toolStats.forEach((name, stat) -> tools.add(new MetaDtos.ToolStatDto(
                name, stat[0], stat[1], round3(stat[2] / (double) stat[0]))));
        tools.sort(Comparator.comparingInt(MetaDtos.ToolStatDto::errors).reversed());

        return new MetaDtos.OverviewDto(traces.size(),
                round3(success / (double) traces.size()),
                round3(silent / (double) traces.size()),
                round1(p50), round1(p95), round1(avgTokens), round1(costPerSuccess),
                round3(avgRetry), round1(avgSpans), round1(avgDecisions), tools);
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
