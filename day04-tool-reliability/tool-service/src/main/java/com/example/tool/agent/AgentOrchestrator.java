package com.example.tool.agent;

import com.example.tool.config.ToolProperties;
import com.example.tool.exec.ExecutionContext;
import com.example.tool.exec.ParallelToolExecutor;
import com.example.tool.exec.ResilienceRegistry;
import com.example.tool.exec.RunPolicy;
import com.example.tool.exec.ToolCall;
import com.example.tool.exec.ToolResult;
import com.example.tool.repair.ArgumentRepairer;
import com.example.tool.repair.Repair;
import com.example.tool.repair.RepairOutcome;
import com.example.tool.spec.JsonSchemaValidator;
import com.example.tool.spec.ToolRegistry;
import com.example.tool.spec.ToolSpec;
import com.example.tool.spec.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排器:规划 → 参数预检(可回灌一轮)→ 依赖就绪并行执行 → 汇总。
 *
 * <p>为什么参数预检放在编排层而不是工具层:只有编排层手里有"任务原文"。
 * 参数缺了 orderId,工具层只能报错;编排层可以说"这个任务里明明写着 A10003"。
 * 这是"自纠正"和"报错"的本质区别。</p>
 *
 * <p>但预检只回灌一次。两轮还改不对,继续问只会烧钱,而且大概率是任务本身不可完成
 * (比如用户要退的订单根本不存在),这时候正确的动作是转人工,不是重试。</p>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final Planner planner;
    private final ToolRegistry registry;
    private final ArgumentRepairer repairer;
    private final ParallelToolExecutor executor;
    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final ToolProperties props;
    private final ResilienceRegistry resilience;

    public AgentOrchestrator(Planner planner, ToolRegistry registry, ArgumentRepairer repairer,
                             ParallelToolExecutor executor, ToolProperties props,
                             ResilienceRegistry resilience) {
        this.planner = planner;
        this.registry = registry;
        this.repairer = repairer;
        this.executor = executor;
        this.props = props;
        this.resilience = resilience;
    }

    public TaskOutcome run(String task, String requestId, String taskKey,
                           RunPolicy policy, EventSink sink) {
        long t0 = System.currentTimeMillis();
        ExecutionContext ctx = new ExecutionContext(requestId, "-", requestId,
                t0 + props.agent().budgetMs(), t0, sink);

        sink.emit(AgentEvent.of("plan", 0, Map.of("task", task, "policy", policy.name())));

        List<ToolCall> planned = planner.plan(task, taskKey);
        List<Map<String, Object>> planView = new ArrayList<>();
        for (ToolCall c : planned) {
            planView.add(Map.of("callId", c.id(), "tool", c.tool(), "dependsOn", c.dependsOn()));
        }
        sink.emit(AgentEvent.of("planned", ctx.elapsedMs(), Map.of("calls", planView)));

        List<ToolCall> prepared = new ArrayList<>(planned.size());
        int repairCount = 0;
        for (ToolCall call : planned) {
            Prepared p = preflight(call, task, policy, ctx);
            prepared.add(p.call());
            repairCount += p.repairs();
        }

        ParallelToolExecutor.BatchResult batch = executor.execute(prepared, ctx, policy);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("wallMs", batch.wallMs());
        summary.put("waves", batch.waves());
        summary.put("maxConcurrent", batch.maxConcurrent());
        summary.put("calls", prepared.size());
        summary.put("degraded", batch.degradedCount());
        sink.emit(AgentEvent.of("batch_summary", ctx.elapsedMs(), summary));

        String answer = planner.summarize(task, batch.results());
        sink.emit(AgentEvent.of("answer", ctx.elapsedMs(), Map.of("text", answer)));

        TaskOutcome.Status status;
        long degraded = batch.results().stream().filter(ToolResult::fallback).count();
        boolean allOk = batch.results().stream().allMatch(ToolResult::ok);
        if (!allOk) {
            status = TaskOutcome.Status.FAILED;
        } else if (degraded > 0) {
            status = TaskOutcome.Status.DEGRADED;
        } else {
            status = TaskOutcome.Status.SUCCESS;
        }

        int retries = batch.results().stream().mapToInt(r -> Math.max(0, r.attempts() - 1)).sum();
        int repairs = repairCount + batch.results().stream()
                .mapToInt(r -> r.repairs() == null ? 0 : r.repairs().size()).sum();
        long wallMs = System.currentTimeMillis() - t0;

        TaskOutcome outcome = new TaskOutcome(requestId, task, answer, status, batch.results(),
                wallMs, prepared.size(), retries, repairs, (int) degraded);

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", status.name());
        done.put("wallMs", wallMs);
        done.put("retries", retries);
        done.put("repairs", repairs);
        done.put("degraded", degraded);
        sink.emit(AgentEvent.of("done", ctx.elapsedMs(), done));

        log.info("任务 {} 完成: status={} calls={} retries={} repairs={} wall={}ms",
                requestId, status, prepared.size(), retries, repairs, wallMs);
        return outcome;
    }

    private record Prepared(ToolCall call, int repairs) {
    }

    private Prepared preflight(ToolCall call, String task, RunPolicy policy, ExecutionContext ctx) {
        ToolSpec spec = registry.requireSpec(call.tool());
        List<Violation> violations = onlyResolvable(validator.validate(call.args(), spec.schema()), call.args());
        if (violations.stream().noneMatch(Violation::blocking)) {
            return new Prepared(call, 0);
        }
        int repairs = 0;
        ObjectNode args = call.args();
        ToolCall effective = call;

        if (policy.repair()) {
            RepairOutcome outcome = repairer.repair(args, spec.schema());
            effective = call.withArgs(outcome.args());
            repairs += outcome.applied().size();
            emitRepairs(ctx, call, outcome.applied());
            violations = onlyResolvable(outcome.remaining(), effective.args());

            if (violations.stream().anyMatch(Violation::blocking)) {
                ToolCall rewritten = planner.rewrite(effective, violations, task);
                sink(ctx, "rewrite", Map.of(
                        "callId", call.id(),
                        "tool", call.tool(),
                        "violations", violations.stream().map(Violation::toString).toList()));
                RepairOutcome second = repairer.repair(rewritten.args(), spec.schema());
                effective = rewritten.withArgs(second.args());
                repairs += second.applied().size();
                emitRepairs(ctx, call, second.applied());
            }
        }
        return new Prepared(effective, repairs);
    }

    /**
     * 参数里还留着 {@code $c1.paidAmount} 这种占位符时,类型当然对不上 —— 这不是模型的错,
     * 是这个字段要到执行时才能求值。把它当参数错误去修/去回灌,只会白白多问模型一轮。
     * 所以预检阶段先放过引用字段,真正的校验留给执行前那一刻。
     */
    private List<Violation> onlyResolvable(List<Violation> violations, ObjectNode args) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : violations) {
            if (isReference(args, v.path())) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    private boolean isReference(ObjectNode args, String path) {
        JsonNode node = args;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = node == null ? null : node.get(segment);
        }
        return node != null && node.isTextual() && node.asText().matches("^\\$[A-Za-z0-9_]+\\..+$");
    }

    private void emitRepairs(ExecutionContext ctx, ToolCall call, List<Repair> applied) {
        if (applied.isEmpty()) {
            return;
        }
        // 编排层的修复也要记进指标,否则报表上"修复 0 次"而实际修了一大堆
        resilience.metrics(call.tool()).onRepair(applied.size());
        sink(ctx, "repair", Map.of(
                "callId", call.id(),
                "tool", call.tool(),
                "repairs", applied.stream().map(Repair::toString).toList()));
    }

    private void sink(ExecutionContext ctx, String type, Map<String, Object> data) {
        if (ctx.sink() != null) {
            ctx.sink().emit(AgentEvent.of(type, ctx.elapsedMs(), new LinkedHashMap<>(data)));
        }
    }
}
