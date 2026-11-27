package com.example.agent.pipeline;

import com.example.o11y.span.Outcome;
import com.example.o11y.triage.TriageEngine;

/**
 * 一次运行的对外结果。
 *
 * <p>{@code culpritSpanId} 只给评测用:API 层不会把它吐给前端,
 * 否则页面就成了"看答案",不是"帮你定位"。</p>
 */
public record RunResult(String traceId, String answer, Outcome outcome, double trust,
                        TriageEngine.Triage triage, String culpritSpanId, String culpritReason) {
}
