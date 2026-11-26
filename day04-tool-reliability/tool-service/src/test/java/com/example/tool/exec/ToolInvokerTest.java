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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.tool.support.TestSupport.obj;
import static com.example.tool.support.TestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class ToolInvokerTest {

    private final JsonSchema schema = JsonSchema.object(
            new LinkedHashMap<>(Map.of("id", JsonSchema.string(""))), "id");

    private ToolProperties props;
    private ToolRegistry registry;
    private ResilienceRegistry resilience;
    private FaultInjector faults;
    private ToolInvoker invoker;

    private final AtomicInteger writeCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        props = props();
        registry = new ToolRegistry();
        resilience = new ResilienceRegistry(props);
        faults = new FaultInjector(props);
        invoker = new ToolInvoker(registry, new RetryPolicy(props), resilience, faults, props,
                new ArgumentRepairer());

        registry.register(ToolSpec.read("ok", "总是成功", schema),
                (args, ctx) -> JsonNodeFactory.instance.objectNode().put("v", 1));

        AtomicInteger flaky = new AtomicInteger();
        registry.register(ToolSpec.read("flaky", "前两次失败", schema), (args, ctx) -> {
            if (flaky.incrementAndGet() <= 2) {
                throw ToolException.upstream("boom " + flaky.get());
            }
            return JsonNodeFactory.instance.objectNode().put("v", 2);
        });

        registry.register(ToolSpec.read("slow", "很慢", schema), (args, ctx) -> {
            sleep(500);
            return JsonNodeFactory.instance.objectNode();
        });

        registry.register(ToolSpec.write("writer", "非幂等写", schema, false), (args, ctx) -> {
            writeCalls.incrementAndGet();
            throw ToolException.timeout(300);
        });
    }

    private ExecutionContext ctx() {
        return ExecutionContext.of("test", 5000);
    }

    @Test
    void 成功时返回结果并记录指标() {
        ToolResult r = invoker.invoke(ToolCall.of("c1", "ok", obj("id", "A1")), ctx(), RunPolicy.FULL);

        assertThat(r.ok()).isTrue();
        assertThat(r.payload().get("v").asInt()).isEqualTo(1);
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(resilience.metrics("ok").snapshot().calls()).isEqualTo(1);
        assertThat(resilience.metrics("ok").snapshot().successes()).isEqualTo(1);
    }

    @Test
    void 前两次失败第三次成功() {
        ToolResult r = invoker.invoke(ToolCall.of("c1", "flaky", obj("id", "A1")), ctx(), RunPolicy.FULL);

        assertThat(r.ok()).isTrue();
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(r.attemptLog()).hasSize(3);
        assertThat(r.attemptLog().get(0).ok()).isFalse();
        assertThat(r.attemptLog().get(2).ok()).isTrue();
        assertThat(resilience.metrics("flaky").snapshot().retries()).isEqualTo(2);
    }

    @Test
    void 超时重试到上限后失败() {
        // 关掉降级,才能看清重试本身的结果
        RunPolicy retryOnly = RunPolicy.of("retry", true, true, false, false, false);
        ToolResult r = invoker.invoke(ToolCall.of("c1", "slow", obj("id", "A1")), ctx(), retryOnly);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorKind()).isEqualTo(ErrorKind.TIMEOUT.name());
        assertThat(r.attempts()).isEqualTo(3);
    }

    @Test
    void 非幂等写操作只尝试一次() {
        ToolResult r = invoker.invoke(ToolCall.of("c1", "writer", obj("id", "A1")), ctx(), RunPolicy.FULL);

        assertThat(r.ok()).isFalse();
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(writeCalls.get()).isEqualTo(1);
    }

    @Test
    void 读操作失败可以降级为部分结果() {
        RunPolicy policy = RunPolicy.of("no-retry+fallback", false, true, false, false, true);
        ToolResult r = invoker.invoke(ToolCall.of("c1", "slow", obj("id", "A1")), ctx(), policy);

        assertThat(r.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(r.fallback()).isTrue();
        assertThat(r.payload().get("degraded").asBoolean()).isTrue();
        assertThat(resilience.metrics("slow").snapshot().fallbacks()).isEqualTo(1);
    }

    @Test
    void 写操作绝不降级() {
        RunPolicy policy = RunPolicy.of("fallback", false, true, false, false, true);
        ToolResult r = invoker.invoke(ToolCall.of("c1", "writer", obj("id", "A1")), ctx(), policy);

        assertThat(r.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(r.fallback()).isFalse();
    }

    @Test
    void 熔断打开后快速失败不再打上游() {
        runWithForcedFailure();

        long t0 = System.currentTimeMillis();
        RunPolicy noFallback = RunPolicy.of("retry+breaker", true, true, false, true, false);
        ToolResult r = invoker.invoke(ToolCall.of("c-last", "ok", obj("id", "A1")), ctx(), noFallback);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(r.ok()).isFalse();
        assertThat(r.errorKind()).isEqualTo(ErrorKind.CIRCUIT_OPEN.name());
        assertThat(r.circuitRejected()).isTrue();
        // 快速失败的证据:没有真的去执行(执行需要 3 次重试 + 退避)
        assertThat(elapsed).isLessThan(60);
    }

    private void runWithForcedFailure() {
        faults.force(new FaultInjector.ForcedFault("ok", ErrorKind.RATE_LIMIT, "forced 429"));
        for (int i = 0; i < 10; i++) {
            invoker.invoke(ToolCall.of("c" + i, "ok", obj("id", "A1")), ctx(), RunPolicy.FULL);
        }
        assertThat(resilience.breaker("ok").snapshot().state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 预算耗尽时不再发起新尝试() {
        long now = System.currentTimeMillis();
        ExecutionContext tight = new ExecutionContext("t", "c1", "k", now + 30, now, null);
        ToolResult r = invoker.invoke(ToolCall.of("c1", "slow", obj("id", "A1")), tight, RunPolicy.FULL);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorKind()).isEqualTo(ErrorKind.BUDGET_EXCEEDED.name());
    }

    @Test
    void 参数错误不会打到工具实现() {
        AtomicInteger called = new AtomicInteger();
        registry.register(ToolSpec.read("strict", "需要 id", schema), (args, ctx) -> {
            called.incrementAndGet();
            return JsonNodeFactory.instance.objectNode();
        });
        RunPolicy noRepair = RunPolicy.of("no-repair", true, false, false, false, false);
        ToolResult r = invoker.invoke(ToolCall.of("c1", "strict", obj("other", "x")), ctx(), noRepair);

        assertThat(r.errorKind()).isEqualTo(ErrorKind.BAD_ARGUMENT.name());
        assertThat(called.get()).isZero();
    }

    @Test
    void 参数可修复时自动修好再执行() {
        registry.register(ToolSpec.read("lenient", "需要 id", schema),
                (args, ctx) -> JsonNodeFactory.instance.objectNode().put("id", args.get("id").asText()));
        ToolResult r = invoker.invoke(ToolCall.of("c1", "lenient", obj("id", 123)), ctx(), RunPolicy.FULL);

        assertThat(r.ok()).isTrue();
        assertThat(r.payload().get("id").asText()).isEqualTo("123");
        assertThat(r.repairs()).isNotEmpty();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
