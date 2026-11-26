package com.example.tool.exec;

import com.example.tool.config.ToolProperties;
import com.example.tool.fault.FaultInjector;
import com.example.tool.repair.ArgumentRepairer;
import com.example.tool.spec.JsonSchema;
import com.example.tool.spec.ToolSpec;
import com.example.tool.spec.ToolRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.example.tool.support.TestSupport.obj;
import static com.example.tool.support.TestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class ParallelToolExecutorTest {

    private final JsonSchema orderSchema = JsonSchema.object(
            new LinkedHashMap<>(Map.of("orderId", JsonSchema.string(""))), "orderId");
    private final JsonSchema quoteSchema = JsonSchema.object(new LinkedHashMap<>(Map.of(
            "orderId", JsonSchema.string(""),
            "paidAmount", JsonSchema.number("", 0, 100000))), "orderId");
    private final JsonSchema submitSchema = JsonSchema.object(
            new LinkedHashMap<>(Map.of("quoteId", JsonSchema.string(""))), "quoteId");

    private ToolRegistry registry;
    private ParallelToolExecutor executor;
    private FaultInjector faults;
    private final AtomicReference<Double> seenAmount = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ToolProperties props = props();
        registry = new ToolRegistry();
        ResilienceRegistry resilience = new ResilienceRegistry(props);
        faults = new FaultInjector(props);
        ToolInvoker invoker = new ToolInvoker(registry, new RetryPolicy(props), resilience, faults, props,
                new ArgumentRepairer());
        executor = new ParallelToolExecutor(invoker, props);

        registry.register(ToolSpec.read("order.query", "", orderSchema), (args, ctx) -> {
            sleep(100);
            return JsonNodeFactory.instance.objectNode()
                    .put("orderId", args.get("orderId").asText())
                    .put("paidAmount", 199.0);
        });
        registry.register(ToolSpec.read("logistics.track", "", orderSchema), (args, ctx) -> {
            sleep(200);
            return JsonNodeFactory.instance.objectNode().put("latest", "运输中");
        });
        registry.register(ToolSpec.read("refund.quote", "", quoteSchema), (args, ctx) -> {
            sleep(60);
            seenAmount.set(args.hasNonNull("paidAmount") ? args.get("paidAmount").asDouble() : null);
            return JsonNodeFactory.instance.objectNode().put("quoteId", "Q1");
        });
        registry.register(ToolSpec.read("refund.submit", "", submitSchema), (args, ctx) -> {
            sleep(60);
            return JsonNodeFactory.instance.objectNode().put("refundId", "R1");
        });
    }

    private List<ToolCall> calls() {
        return List.of(
                ToolCall.of("c1", "order.query", obj("orderId", "A10001")),
                new ToolCall("c2", "logistics.track", obj("orderId", "A10001"), List.of()),
                new ToolCall("c3", "refund.quote",
                        obj("orderId", "$c1.orderId", "paidAmount", "$c1.paidAmount"), List.of("c1")),
                new ToolCall("c4", "refund.submit",
                        obj("quoteId", "$c3.quoteId"), List.of("c3")));
    }

    @Test
    void 依赖就绪调度明显快于串行() {
        ExecutionContext ctx = ExecutionContext.of("t1", 10_000);

        ParallelToolExecutor.BatchResult parallel = executor.execute(calls(), ctx, RunPolicy.FULL);
        ParallelToolExecutor.BatchResult serial = executor.execute(calls(), ctx,
                RunPolicy.of("serial", true, true, false, true, true));

        assertThat(parallel.results()).allMatch(ToolResult::ok);
        assertThat(serial.results()).allMatch(ToolResult::ok);
        // 串行 = 100+200+60+60 = 420ms;并行关键路径 = 100+60+60 = 220ms
        assertThat(serial.wallMs()).isGreaterThanOrEqualTo(400);
        assertThat(parallel.wallMs()).isLessThan(serial.wallMs() - 120);
    }

    @Test
    void 参数引用会替换成上游真实值() {
        executor.execute(calls(), ExecutionContext.of("t2", 10_000), RunPolicy.FULL);
        assertThat(seenAmount.get()).isEqualTo(199.0);
    }

    @Test
    void 依赖失败时下游跳过而不是报一堆错() {
        faults.force(new FaultInjector.ForcedFault("order.query", ErrorKind.NOT_FOUND, "no such order"));
        ParallelToolExecutor.BatchResult batch = executor.execute(calls(),
                ExecutionContext.of("t3", 10_000), RunPolicy.FULL);

        ToolResult quote = batch.results().stream().filter(r -> r.callId().equals("c3")).findFirst().orElseThrow();
        ToolResult submit = batch.results().stream().filter(r -> r.callId().equals("c4")).findFirst().orElseThrow();
        assertThat(quote.status()).isEqualTo(ToolResult.Status.SKIPPED);
        assertThat(submit.status()).isEqualTo(ToolResult.Status.SKIPPED);
        assertThat(batch.degradedCount()).isZero();
    }

    @Test
    void 并发上限生效() {
        // 8 个独立调用,并发上限 8,峰值不应该超过 8
        List<ToolCall> many = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(ToolCall.of("x" + i, "order.query", obj("orderId", "A1000" + i)));
        }
        ParallelToolExecutor.BatchResult batch = executor.execute(many,
                ExecutionContext.of("t4", 10_000), RunPolicy.FULL);

        assertThat(batch.results()).allMatch(ToolResult::ok);
        assertThat(batch.maxConcurrent()).isLessThanOrEqualTo(8);
        assertThat(batch.maxConcurrent()).isGreaterThan(1);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
