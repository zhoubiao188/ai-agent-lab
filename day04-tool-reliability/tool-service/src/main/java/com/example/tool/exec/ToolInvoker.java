package com.example.tool.exec;

import com.example.tool.agent.AgentEvent;
import com.example.tool.config.ToolProperties;
import com.example.tool.fault.FaultInjector;
import com.example.tool.repair.ArgumentRepairer;
import com.example.tool.repair.Repair;
import com.example.tool.repair.RepairOutcome;
import com.example.tool.spec.JsonSchemaValidator;
import com.example.tool.spec.ToolHandler;
import com.example.tool.spec.ToolSpec;
import com.example.tool.spec.ToolRegistry;
import com.example.tool.spec.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 工具调用流水线。所有可靠性机制都在这里汇合,顺序是设计出来的,不是随便排的:
 *
 * <pre>
 * 校验/修复 → 预算检查 → 熔断放行 → 故障注入 → 带超时执行 → 分类 → 退避重试 → 降级
 * </pre>
 *
 * <p>几个顺序上的讲究:</p>
 * <p>1、<b>校验在熔断之前</b>。参数错的调用根本没打到上游,不该计入熔断统计,
 * 否则模型抽风一次就会把健康的下游熔断掉。</p>
 * <p>2、<b>预算检查在每次尝试之前</b>。第一次尝试就可能已经把预算耗光了,
 * 这时候还去排重试,只会让用户多等一个注定超时的超时。</p>
 * <p>3、<b>降级只给读操作</b>。写操作降级返回"成功"是最危险的事,那不是降级,那是伪造。</p>
 */
@Component
public class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);

    private final ToolRegistry registry;
    private final RetryPolicy retryPolicy;
    private final ResilienceRegistry resilience;
    private final FaultInjector faults;
    private final ToolProperties props;
    private final ArgumentRepairer repairer;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService handlers;

    public ToolInvoker(ToolRegistry registry, RetryPolicy retryPolicy, ResilienceRegistry resilience,
                       FaultInjector faults, ToolProperties props, ArgumentRepairer repairer) {
        this.registry = registry;
        this.retryPolicy = retryPolicy;
        this.resilience = resilience;
        this.faults = faults;
        this.props = props;
        this.repairer = repairer;
        this.handlers = Executors.newFixedThreadPool(32, r -> {
            Thread t = new Thread(r, "tool-handler");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        handlers.shutdownNow();
    }

    public ToolResult invoke(ToolCall call, ExecutionContext ctx, RunPolicy policy) {
        ToolSpec spec = registry.requireSpec(call.tool());
        ToolHandler handler = registry.requireHandler(call.tool());
        ToolMetrics metrics = resilience.metrics(call.tool());
        CircuitBreaker breaker = resilience.breaker(call.tool());

        long start = System.currentTimeMillis();
        List<ToolResult.Attempt> attemptLog = new ArrayList<>();
        List<Repair> repairs = new ArrayList<>();
        ObjectNode args = call.args() == null ? mapper.createObjectNode() : call.args();

        metrics.onCall();

        // ---- 1. 参数校验与修复 ----
        List<Violation> violations = validator.validate(args, spec.schema());
        if (hasBlocking(violations) && policy.repair()) {
            RepairOutcome outcome = repairer.repair(args, spec.schema());
            args = outcome.args();
            repairs.addAll(outcome.applied());
            violations = outcome.remaining();
            if (outcome.changed()) {
                metrics.onRepair(outcome.applied().size());
                emit(ctx, "repair", Map.of(
                        "callId", call.id(),
                        "tool", call.tool(),
                        "repairs", outcome.applied().stream().map(Repair::toString).toList()));
            }
        }
        if (hasBlocking(violations)) {
            String detail = violations.stream().map(Violation::toString).collect(Collectors.joining("; "));
            emit(ctx, "tool_fail", Map.of("callId", call.id(), "tool", call.tool(),
                    "kind", ErrorKind.BAD_ARGUMENT.name(), "error", detail));
            return ToolResult.failed(call.id(), call.tool(), ErrorKind.BAD_ARGUMENT, detail, 0,
                    start, System.currentTimeMillis(), attemptLog, repairs, false);
        }

        // ---- 2. 尝试循环 ----
        int maxAttempts = policy.retry() ? props.maxAttempts() : 1;
        ToolException last = null;
        long delayBefore = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (ctx.budgetExhausted(50)) {
                last = new ToolException(ErrorKind.BUDGET_EXCEEDED, "任务预算耗尽,放弃调用 " + call.tool());
                break;
            }
            if (policy.breaker() && !breaker.tryAcquire()) {
                metrics.onCircuitReject();
                last = new ToolException(ErrorKind.CIRCUIT_OPEN, "熔断器打开,快速失败");
                emit(ctx, "circuit_open", Map.of("callId", call.id(), "tool", call.tool()));
                break;
            }
            long t0 = System.currentTimeMillis();
            emit(ctx, "tool_start", Map.of("callId", call.id(), "tool", call.tool(), "attempt", attempt));
            try {
                faults.maybeFail(call.tool(), call.id(), attempt);
                JsonNode payload = execute(handler, args, ctx, spec);
                long t1 = System.currentTimeMillis();
                attemptLog.add(new ToolResult.Attempt(attempt, t0, t1, true, null, null, delayBefore));
                if (policy.breaker()) {
                    breaker.onSuccess();
                }
                metrics.onSuccess();
                metrics.recordLatency(t1 - t0);
                emit(ctx, "tool_ok", Map.of("callId", call.id(), "tool", call.tool(),
                        "attempts", attempt, "durationMs", t1 - t0, "at", t1 - ctx.startEpochMs()));
                return ToolResult.ok(call.id(), call.tool(), payload, attempt, start, t1, attemptLog, repairs);
            } catch (ToolException e) {
                long t1 = System.currentTimeMillis();
                attemptLog.add(new ToolResult.Attempt(attempt, t0, t1, false, e.kind().name(),
                        e.getMessage(), delayBefore));
                if (policy.breaker()) {
                    breaker.onFailure();
                }
                metrics.onFailure();
                last = e;
                log.debug("工具 {} 第 {} 次失败: {} {}", call.tool(), attempt, e.kind(), e.getMessage());

                RetryPolicy.Decision decision = policy.retry()
                        ? retryPolicy.decide(attempt, e, spec)
                        : RetryPolicy.Decision.stop("重试机制未开启");
                if (!decision.retry()) {
                    emit(ctx, "tool_fail", Map.of("callId", call.id(), "tool", call.tool(),
                            "kind", e.kind().name(), "error", String.valueOf(e.getMessage()),
                            "attempts", attempt, "reason", decision.reason()));
                    break;
                }
                metrics.onRetry();
                emit(ctx, "retry", Map.of("callId", call.id(), "tool", call.tool(),
                        "attempt", attempt, "delayMs", decision.delayMs(),
                        "kind", e.kind().name(), "reason", decision.reason()));
                delayBefore = decision.delayMs();
                sleep(delayBefore, ctx);
            }
        }

        long end = System.currentTimeMillis();

        // ---- 3. 降级(只给读操作) ----
        if (policy.fallback() && !spec.writeOperation() && last != null && fallbackable(last.kind())) {
            metrics.onFallback();
            ObjectNode degraded = mapper.createObjectNode();
            degraded.put("degraded", true);
            degraded.put("tool", call.tool());
            degraded.put("reason", last.kind().name());
            degraded.put("note", "上游不可用,已返回降级结果,数据可能不完整");
            emit(ctx, "tool_fallback", Map.of("callId", call.id(), "tool", call.tool(),
                    "kind", last.kind().name()));
            return ToolResult.failed(call.id(), call.tool(), last.kind(), last.getMessage(),
                    attemptLog.size(), start, end, attemptLog, repairs, last.kind() == ErrorKind.CIRCUIT_OPEN)
                    .asFallback(degraded);
        }

        ErrorKind kind = last == null ? ErrorKind.UNKNOWN : last.kind();
        return ToolResult.failed(call.id(), call.tool(), kind,
                last == null ? "未知失败" : last.getMessage(),
                attemptLog.size(), start, end, attemptLog, repairs, kind == ErrorKind.CIRCUIT_OPEN);
    }

    private JsonNode execute(ToolHandler handler, ObjectNode args, ExecutionContext ctx, ToolSpec spec) {
        long timeout = spec.timeoutMs() > 0 ? spec.timeoutMs() : props.timeoutMs();
        timeout = Math.max(1, Math.min(timeout, ctx.remainingMs()));
        Future<JsonNode> future;
        try {
            future = handlers.submit(() -> handler.invoke(args, ctx));
        } catch (RejectedExecutionException e) {
            throw ToolException.upstream("工具线程池已满");
        }
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw ToolException.timeout(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException(ErrorKind.UNKNOWN, "调用被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolException te) {
                throw te;
            }
            throw new ToolException(ErrorKind.UNKNOWN, "工具实现抛出异常: " + cause);
        }
    }

    private boolean hasBlocking(List<Violation> violations) {
        return violations.stream().anyMatch(Violation::blocking);
    }

    private boolean fallbackable(ErrorKind kind) {
        return kind == ErrorKind.TIMEOUT || kind == ErrorKind.UPSTREAM_5XX
                || kind == ErrorKind.RATE_LIMIT || kind == ErrorKind.CONNECTION
                || kind == ErrorKind.CIRCUIT_OPEN;
    }

    private void sleep(long ms, ExecutionContext ctx) {
        long capped = Math.min(ms, Math.max(0, ctx.remainingMs() - 30));
        if (capped <= 0) {
            return;
        }
        try {
            Thread.sleep(capped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(ExecutionContext ctx, String type, Map<String, Object> data) {
        if (ctx.sink() != null) {
            Map<String, Object> payload = new HashMap<>(data);
            ctx.sink().emit(AgentEvent.of(type, ctx.elapsedMs(), payload));
        }
    }
}
