package com.example.memory.observe;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 可观测性:只暴露 4 个真正能用来告警的指标。
 * <ul>
 *   <li>{@code agent_request_total} 请求量(按结果分 tag)</li>
 *   <li>{@code agent_llm_latency_seconds} 单次 LLM 调用耗时</li>
 *   <li>{@code agent_tool_invocations_total} 工具调用量(按工具名 + 成败)</li>
 *   <li>{@code agent_tool_latency_seconds} 工具耗时</li>
 * </ul>
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeStreams = new AtomicInteger();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("agent_active_streams", activeStreams);
    }

    public void requestFinished(String result) {
        Counter.builder("agent_request_total")
                .description("Agent 请求总数")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void recordLlmLatency(long nanos) {
        Timer.builder("agent_llm_latency")
                .description("LLM 单次调用耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordToolCall(String tool, boolean success, long nanos) {
        Counter.builder("agent_tool_invocations_total")
                .tag("tool", tool)
                .tag("result", success ? "ok" : "error")
                .register(registry)
                .increment();
        Timer.builder("agent_tool_latency")
                .tag("tool", tool)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void streamOpened() {
        activeStreams.incrementAndGet();
    }

    public void streamClosed() {
        activeStreams.decrementAndGet();
    }
}
