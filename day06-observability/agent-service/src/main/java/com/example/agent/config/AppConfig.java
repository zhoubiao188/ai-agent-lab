package com.example.agent.config;

import com.example.agent.metrics.RunMetrics;
import com.example.agent.pipeline.AgentPipeline;
import com.example.agent.pipeline.SlotBinder;
import com.example.agent.tool.CircuitBreaker;
import com.example.agent.tool.DemoTools;
import com.example.agent.tool.ToolRegistry;
import com.example.o11y.clock.Clock;
import com.example.o11y.clock.SystemClock;
import com.example.o11y.export.JsonlExporter;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.store.TraceStore;
import com.example.o11y.trace.Tracer;
import com.example.o11y.triage.TriageEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, O11yProperties.class})
public class AppConfig {

    @Bean
    public Clock clock() {
        return SystemClock.INSTANCE;
    }

    @Bean
    public Redactor redactor() {
        return new Redactor();
    }

    @Bean
    public TailSampler tailSampler(O11yProperties props) {
        return new TailSampler(props.getSampleRate(), props.getTrustFloor());
    }

    @Bean
    public TraceStore traceStore(O11yProperties props) {
        return new InMemoryTraceStore(props.getStoreCapacity());
    }

    @Bean
    public Tracer tracer(Clock clock, Redactor redactor, TailSampler sampler, TraceStore store) {
        return new Tracer(clock, redactor, sampler, store);
    }

    @Bean
    public TriageEngine triageEngine() {
        return new TriageEngine();
    }

    @Bean
    public JsonlExporter jsonlExporter() {
        return new JsonlExporter();
    }

    @Bean
    public ToolRegistry toolRegistry(DemoTools demoTools) {
        return demoTools.registry();
    }

    /** 故障剧本编译器。线上没有它 —— 它是评测与演示的基础设施,所以显式建 bean 而不是 @Component。 */
    @Bean
    public com.example.agent.fault.FaultInjector faultInjector(AgentProperties props) {
        return new com.example.agent.fault.FaultInjector(props.getBenchSeed());
    }

    @Bean
    public CircuitBreaker circuitBreaker(Clock clock, AgentProperties props) {
        return new CircuitBreaker(clock, 5, 3000);
    }

    /** 并行步骤用的线程池。核心线程数就是并发上限,队列不设容量,避免排队掩盖吞吐问题。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor(AgentProperties props) {
        return Executors.newFixedThreadPool(Math.max(2, props.getParallelism()), r -> {
            Thread t = new Thread(r, "agent-step");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public AgentPipeline agentPipeline(Tracer tracer, ToolRegistry registry, SlotBinder binder,
                                       com.example.agent.fault.FaultInjector injector,
                                       AgentProperties props, TriageEngine triage,
                                       MeterRegistry meterRegistry, CircuitBreaker breaker,
                                       ExecutorService agentExecutor) {
        return new AgentPipeline(tracer, registry, binder, injector, props, triage,
                new RunMetrics(meterRegistry), breaker, agentExecutor);
    }
}
