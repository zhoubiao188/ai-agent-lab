package com.example.agent.support;

import com.example.agent.config.AgentProperties;
import com.example.agent.fault.FaultInjector;
import com.example.agent.fault.Scenario;
import com.example.agent.metrics.RunMetrics;
import com.example.agent.pipeline.AgentPipeline;
import com.example.agent.pipeline.RunResult;
import com.example.agent.pipeline.SlotBinder;
import com.example.agent.tool.CircuitBreaker;
import com.example.agent.tool.DemoTools;
import com.example.o11y.clock.VirtualClock;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.Trace;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.trace.Tracer;
import com.example.o11y.triage.TriageEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试夹具:虚拟时钟 + 独立存储 + 独立熔断/幂等状态。
 *
 * <p>每条用例都要"从干净状态开始",否则一条用例留下的熔断状态会让下一条莫名其妙地降级 ——
 * 这种测试最难查:单跑绿、连跑红。</p>
 */
public final class PipelineFixture implements AutoCloseable {

    public final VirtualClock clock = new VirtualClock();
    public final InMemoryTraceStore store = new InMemoryTraceStore(64);
    public final TriageEngine triage = new TriageEngine();
    public final DemoTools tools = new DemoTools();
    public final ExecutorService pool = Executors.newFixedThreadPool(2);
    public final Tracer tracer;
    public final AgentPipeline pipeline;

    public PipelineFixture(long seed) {
        this.tracer = new Tracer(clock, new Redactor(), new TailSampler(1.0, 0.0), store);
        AgentProperties props = new AgentProperties();
        this.pipeline = new AgentPipeline(tracer, tools.registry(), new SlotBinder(),
                new FaultInjector(seed), props, triage, RunMetrics.noop(),
                new CircuitBreaker(clock, 5, 3000), pool);
    }

    public RunResult run(Scenario scenario, String question) {
        return pipeline.run(question, scenario, "u-test", "T-" + scenario.name());
    }

    public Trace traceOf(RunResult result) {
        return store.find(result.traceId()).orElseThrow();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
