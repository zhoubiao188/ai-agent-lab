package com.example.agent.api;

import com.example.agent.api.dto.DecisionDto;
import com.example.agent.api.dto.RunSummaryDto;
import com.example.agent.api.dto.SpanDto;
import com.example.agent.api.dto.SuspectDto;
import com.example.agent.api.dto.TraceDetail;
import com.example.agent.fault.Scenario;
import com.example.o11y.decision.Decision;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.triage.Suspect;
import com.example.o11y.triage.TriageEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 领域对象 → 接口模型。
 *
 * <p>刻意不让 Span 直接序列化出去。原因有两个:一是 Span 里有一堆内部字段
 * (redactions、parentId 之类的空值)会污染响应;二是线上迟早要改字段,
 * 直接暴露领域对象意味着前端跟着一起改。多一层映射,换来的是一份稳定契约。</p>
 */
@Component
public class TraceMapper {

    public TraceDetail detail(Trace trace, TriageEngine.Triage triage) {
        long start = trace.root().startNanos();
        List<SpanDto> spans = trace.spans().stream().map(s -> span(s, start, trace.startedEpochMilli())).toList();
        List<DecisionDto> decisions = trace.decisions().stream().map(this::decision).toList();
        List<SuspectDto> suspects = triage.suspects().stream().map(this::suspect).toList();
        return new TraceDetail(
                trace.traceId(), trace.taskId(), trace.scenario(),
                scenarioLabel(trace.scenario()), trace.question(), trace.userId(),
                trace.startedEpochMilli(), trace.outcome().name(), trace.outcome().label(),
                trace.trust(), trace.answer(), round1(trace.totalMs()),
                trace.totalTokens(), trace.totalCostMicros(),
                spans.size(), decisions.size(), trace.retryAmplification(), trace.sampled(),
                triage.narrative(), spans, decisions, suspects);
    }

    public RunSummaryDto summary(Trace trace, TriageEngine.Triage triage) {
        Suspect top = triage.suspects().isEmpty() ? null : triage.suspects().get(0);
        boolean silent = trace.outcome() == com.example.o11y.span.Outcome.SUCCESS
                && trace.spans().stream().anyMatch(s -> !s.integrityOk());
        return new RunSummaryDto(
                trace.traceId(), trace.taskId(), trace.scenario(), scenarioLabel(trace.scenario()),
                trace.outcome().name(), trace.outcome().label(), trace.startedEpochMilli(),
                round1(trace.totalMs()), trace.trust(), trace.spans().size(),
                trace.totalTokens(), trace.totalCostMicros(), trace.retryAmplification(),
                silent, top == null ? "-" : top.name(), top == null ? "-" : top.kind().label());
    }

    public SpanDto span(Span span, long rootStartNanos, long traceStartEpochMilli) {
        List<SpanDto.EventDto> events = new ArrayList<>();
        span.events().forEach(e -> events.add(new SpanDto.EventDto(e.name(),
                e.epochMilli() - traceStartEpochMilli, e.attrs())));
        return new SpanDto(
                span.spanId(), span.parentId(), span.name(), span.kind().name(), span.kind().label(),
                span.decisionCode(),
                round3((span.startNanos() - rootStartNanos) / 1_000_000.0),
                round3(span.durationNanos() / 1_000_000.0),
                round3(span.selfNanos() / 1_000_000.0),
                span.status() == SpanStatus.UNSET ? "OK" : span.status().name(),
                span.errorKind(), span.errorMessage(), span.attempts(),
                span.tokensIn(), span.tokensOut(), span.costMicros(), span.integrityOk(),
                span.integrityGrade().name(), span.redactions(), span.attrs(), events);
    }

    public DecisionDto decision(Decision d) {
        List<DecisionDto.AlternativeDto> alternatives = d.alternatives().stream()
                .map(a -> new DecisionDto.AlternativeDto(a.name(), a.score(), a.reason()))
                .toList();
        return new DecisionDto(d.seq(), d.code(), d.point().title(), d.point().question(),
                d.point().symptom(), d.spanId(), d.chosen(), d.basis().name(), d.basis().label(),
                d.confidence(), d.margin(), d.latencyMs(), alternatives, d.attrs());
    }

    public SuspectDto suspect(Suspect s) {
        return new SuspectDto(s.spanId(), s.name(), s.kind().name(), s.kind().label(),
                s.decisionCode(), s.score(), s.contributions(), s.evidence());
    }

    public static String scenarioLabel(String scenario) {
        try {
            return Scenario.valueOf(scenario).label();
        } catch (RuntimeException e) {
            return scenario;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
