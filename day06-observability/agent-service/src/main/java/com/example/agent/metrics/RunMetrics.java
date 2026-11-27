package com.example.agent.metrics;

import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.triage.TriageEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标出口。追踪回答"这一次怎么了",指标回答"这一类是不是在变坏"。
 *
 * <p>三个指标是本文最看重的,它们不在任何标准 RED/USE 模板里:</p>
 * <p>1、{@code agent.silent.failures}:状态成功但完整性校验没过。这个数只要不是 0,
 * 就说明有 bug 在偷偷拿用户的钱;</p>
 * <p>2、{@code agent.retry.amplification}:平均每次工具调用实际打了几次。
 * 它涨了但错误率没涨,说明你在为一个抖动频繁付费;</p>
 * <p>3、{@code agent.cost.per.success}:每成功一次花多少钱。比"总 token"有用得多,
 * 因为总 token 会被流量带偏,而它不会。</p>
 */
@Component
public class RunMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 评测时用:不写指标,避免 200 次注入污染线上口径。 */
    public static RunMetrics noop() {
        return new RunMetrics(null);
    }

    public void record(Trace trace, TriageEngine.Triage triage) {
        if (registry == null) {
            return;
        }
        boolean silent = trace.outcome() == com.example.o11y.span.Outcome.SUCCESS
                && trace.spans().stream().anyMatch(s -> !s.integrityOk());

        counter("agent.runs", "scenario", trace.scenario(), "outcome", trace.outcome().name()).increment();
        if (silent) {
            counter("agent.silent.failures", "scenario", trace.scenario()).increment();
        }

        Timer.builder("agent.run.duration")
                .tag("scenario", trace.scenario())
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofNanos(trace.root().durationNanos()));

        DistributionSummary.builder("agent.run.tokens")
                .baseUnit("tokens")
                .tag("scenario", trace.scenario())
                .publishPercentileHistogram()
                .register(registry)
                .record(trace.totalTokens());

        DistributionSummary.builder("agent.retry.amplification")
                .tag("scenario", trace.scenario())
                .register(registry)
                .record(trace.retryAmplification());

        if (trace.outcome() == com.example.o11y.span.Outcome.SUCCESS) {
            Counter cost = counter("agent.cost.micros", "scenario", trace.scenario());
            cost.increment(trace.totalCostMicros());
        }

        for (Span span : trace.spans()) {
            if (span.kind() != SpanKind.TOOL && span.kind() != SpanKind.WRITE) {
                continue;
            }
            counter("agent.tool.calls", "tool", span.name().replaceFirst("^(tool|write) ", ""),
                    "status", span.status() == SpanStatus.ERROR ? "error" : "ok").increment();
        }

        if (!triage.suspects().isEmpty()) {
            DistributionSummary.builder("agent.triage.top.score")
                    .tag("outcome", trace.outcome().name())
                    .register(registry)
                    .record(triage.suspects().get(0).score());
        }
    }

    private Counter counter(String name, String... tags) {
        String key = name + String.join(",", tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
    }
}
