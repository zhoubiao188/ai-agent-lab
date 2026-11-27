package com.example.multiagent.orch;

import com.example.multiagent.agent.AgentEvent;
import com.example.multiagent.agent.EventSink;
import com.example.multiagent.config.CoordProperties;
import com.example.multiagent.config.ToolProperties;
import com.example.multiagent.coverage.CoverageReport;
import com.example.multiagent.coverage.IntentCoverage;
import com.example.multiagent.critic.Critique;
import com.example.multiagent.critic.Critic;
import com.example.multiagent.critic.Verdict;
import com.example.multiagent.exec.ExecutionContext;
import com.example.multiagent.exec.RunPolicy;
import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.PlanFlaw;
import com.example.multiagent.plan.PlanValidator;
import com.example.multiagent.plan.PlanViolation;
import com.example.multiagent.plan.Step;
import com.example.multiagent.planner.PlanFlawInjector;
import com.example.multiagent.planner.Planner;
import com.example.multiagent.spec.ToolRegistry;
import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 协调器:规划 → 校验 → 覆盖核对 → 执行 → 复核 → 重规划。
 *
 * <p>流水线的顺序同样是设计出来的:</p>
 * <p>1、<b>校验在覆盖之前</b>。结构都错的计划,讨论"漏没漏项"没有意义。</p>
 * <p>2、<b>覆盖核对在补步之前</b>,补步只补"核对出来的差额",不做额外的自由发挥。</p>
 * <p>3、<b>复核在执行之后</b>。有些错只有跑完才看得见:声明产出 {@code amount} 结果没有、
 * 写操作退了个编出来的数字。</p>
 * <p>4、<b>重规划复用已完成步骤的结果</b>。否则"重规划"就等于"把写操作重放一遍",
 * 那是重复退款的生产事故,不是修复。</p>
 */
@Component
public class Coordinator {

    private static final Logger log = LoggerFactory.getLogger(Coordinator.class);

    private final Planner planner;
    private final PlanFlawInjector flawInjector;
    private final PlanValidator validator;
    private final IntentCoverage coverage;
    private final Critic critic;
    private final StepExecutor executor;
    private final GoldenCheck golden;
    private final ToolRegistry registry;
    private final CoordProperties props;
    private final ToolProperties toolProps;

    public Coordinator(Planner planner, PlanFlawInjector flawInjector, PlanValidator validator,
                       IntentCoverage coverage, Critic critic, StepExecutor executor, GoldenCheck golden,
                       ToolRegistry registry, CoordProperties props, ToolProperties toolProps) {
        this.planner = planner;
        this.flawInjector = flawInjector;
        this.validator = validator;
        this.coverage = coverage;
        this.critic = critic;
        this.executor = executor;
        this.golden = golden;
        this.registry = registry;
        this.props = props;
        this.toolProps = toolProps;
    }

    public TaskResult run(TaskSpec task, CoordPolicy policy, EventSink sink) {
        long t0 = System.currentTimeMillis();
        String requestId = task.id() + "-" + System.nanoTime() % 100000;
        ExecutionContext ctx = new ExecutionContext(requestId, "-",
                "idem-" + task.id(), t0 + props.budgetMs(), t0, sink);

        Plan clean = planner.plan(task);
        PlanFlaw flaw = task.forcedFlaw();
        Plan plan = flawInjector.inject(clean, flaw);

        emit(sink, ctx, "task", data("taskId", task.id(), "request", task.request(),
                "slots", task.slots(), "policy", policy.name()));
        emitPlan(sink, ctx, plan);
        if (flaw != null) {
            emit(sink, ctx, "plan_flaw", data("flaw", flaw.name(), "label", flaw.label(),
                    "symptom", flaw.symptom()));
        }

        // ---- 1. 静态校验(拦下就重规划,而不是拦下就报错) ----
        int replans = 0;
        if (policy.validate()) {
            List<PlanViolation> violations = validator.validate(plan);
            if (!violations.isEmpty()) {
                emit(sink, ctx, "validate_fail", data("violations",
                        violations.stream().map(PlanViolation::toString).toList()));
                if (!policy.replan()) {
                    // 只能拦不能改 —— 这就是"加了校验但成功率反而下降"的那一组
                    return conclude(task, policy, plan, List.of(), null, 0, t0, "VALIDATION_BLOCKED", sink, ctx);
                }
                Plan repaired = planner.plan(task);
                if (repaired.fingerprint().equals(plan.fingerprint())) {
                    emit(sink, ctx, "replan_abort", data("reason", "重规划结果与原计划一致,停止重试"));
                    return conclude(task, policy, plan, List.of(), null, 0, t0, "VALIDATION_BLOCKED", sink, ctx);
                }
                replans++;
                emit(sink, ctx, "replan", data("round", replans, "reason", "计划结构不合法,重新规划"));
                plan = repaired;
                emitPlan(sink, ctx, plan);
                List<PlanViolation> again = validator.validate(plan);
                if (!again.isEmpty()) {
                    emit(sink, ctx, "validate_fail", data("violations",
                            again.stream().map(PlanViolation::toString).toList()));
                    return conclude(task, policy, plan, List.of(), null, replans, t0,
                            "VALIDATION_BLOCKED", sink, ctx);
                }
            }
            emit(sink, ctx, "validate_ok", data("steps", plan.size()));
        }

        // ---- 2. 覆盖核对(+机械补步) ----
        if (policy.coverage()) {
            CoverageReport report = coverage.check(plan, task);
            emit(sink, ctx, "coverage", data("required", report.required(),
                    "covered", report.covered(), "missing", report.missing(), "ratio", report.ratio()));
            if (!report.complete()) {
                if (!policy.patch()) {
                    emit(sink, ctx, "coverage_fail", data("missing", report.missing()));
                    return conclude(task, policy, plan, List.of(), null, 0, t0, "UNDER_COVER", sink, ctx);
                }
                plan = coverage.patch(plan, task, report);
                emit(sink, ctx, "coverage_patch", data("added", report.missing(), "steps", plan.size()));
                emitPlan(sink, ctx, plan);
            }
        }

        // ---- 3. 执行 ----
        RunPolicy runPolicy = RunPolicy.FULL;
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, StepOutcome> outcomes = new LinkedHashMap<>();
        Set<String> done = new LinkedHashSet<>();
        execute(plan, ctx, runPolicy, outputs, outcomes, done, sink);

        // ---- 4. 复核 + 修复 ----
        Critique critique = null;
        if (policy.critic()) {
            List<StepOutcome> current = new ArrayList<>(outcomes.values());
            critique = critic.review(plan, task, current);
            emit(sink, ctx, "critic", data("verdict", critique.verdict().name(),
                    "findings", critique.findings()));

            if (critique.verdict() == Verdict.PATCH && policy.patch()) {
                plan = coverage.patch(plan, task, coverage.check(plan, task));
                emit(sink, ctx, "repair_patch", data("steps", plan.size()));
                execute(plan, ctx, runPolicy, outputs, outcomes, done, sink);
                critique = critic.review(plan, task, new ArrayList<>(outcomes.values()));
            } else if (critique.verdict() == Verdict.REPLAN && policy.replan()) {
                Plan replanned = planner.plan(task);
                if (replanned.fingerprint().equals(plan.fingerprint())) {
                    // 重规划拿回一份一模一样的计划:这时候继续重试就是原地打转
                    emit(sink, ctx, "replan_abort", data("reason", "重规划结果与原计划一致,停止重试"));
                } else {
                    replans++;
                    emit(sink, ctx, "replan", data("round", replans, "reason", critique.summary()));
                    plan = replanned;
                    emitPlan(sink, ctx, plan);
                    execute(plan, ctx, runPolicy, outputs, outcomes, done, sink);
                    critique = critic.review(plan, task, new ArrayList<>(outcomes.values()));
                    emit(sink, ctx, "critic", data("verdict", critique.verdict().name(),
                            "findings", critique.findings()));
                }
            }
        }

        return conclude(task, policy, plan, new ArrayList<>(outcomes.values()), critique, replans, t0,
                null, sink, ctx);
    }

    /** 收尾:金标准判定 + 事件 + 结果对象 */
    private TaskResult conclude(TaskSpec task, CoordPolicy policy, Plan plan, List<StepOutcome> outcomes,
                                Critique critique, int replans, long t0, String blocked, EventSink sink,
                                ExecutionContext ctx) {
        boolean claimed = !outcomes.isEmpty() && outcomes.stream().allMatch(StepOutcome::ok);
        GoldenCheck.Judgement judgement = outcomes.isEmpty()
                ? new GoldenCheck.Judgement(false, Set.of(blocked == null ? "NO_EXECUTION" : blocked))
                : golden.judge(task, plan, outcomes);

        Set<String> reasons = new LinkedHashSet<>(judgement.reasons());
        if (blocked != null && outcomes.isEmpty()) {
            reasons.add(blocked);
        }

        String answer = buildAnswer(task, plan, outcomes, judgement.success(), reasons);
        emit(sink, ctx, "answer", data("text", answer, "success", judgement.success(),
                "claimed", claimed, "reasons", reasons));

        long duration = System.currentTimeMillis() - t0;
        emit(sink, ctx, "done", data("success", judgement.success(), "claimed", claimed,
                "durationMs", duration, "steps", outcomes.size(), "replans", replans));

        return new TaskResult(task.id(), policy.name(), task.hasFlaw() ? task.forcedFlaw().name() : "CLEAN",
                judgement.success(), claimed, reasons, plan, outcomes, critique, replans, duration,
                outcomes.size());
    }

    /**
     * 按拓扑序执行。已经成功过的步骤直接复用结果 —— 这是重规划安全的唯一保证。
     */
    private void execute(Plan plan, ExecutionContext ctx, RunPolicy runPolicy,
                         Map<String, JsonNode> outputs, Map<String, StepOutcome> outcomes,
                         Set<String> done, EventSink sink) {
        Optional<List<Step>> ordered = plan.topological();
        if (ordered.isEmpty()) {
            emit(sink, ctx, "plan_invalid", data("reason", "拓扑排序失败"));
            return;
        }
        for (Step step : ordered.get()) {
            if (done.contains(step.id())) {
                emit(sink, ctx, "step_cached", data("stepId", step.id(), "tool", step.tool()));
                continue;
            }
            boolean depFailed = step.dependsOn().stream()
                    .anyMatch(dep -> {
                        StepOutcome o = outcomes.get(dep);
                        return o != null && !o.ok();
                    });
            if (depFailed) {
                StepOutcome skipped = StepOutcome.skipped(step.id(), step.tool(), "上游失败,跳过");
                outcomes.put(step.id(), skipped);
                emit(sink, ctx, "step_skip", data("stepId", step.id(), "tool", step.tool(),
                        "reason", "上游失败"));
                continue;
            }
            // 非幂等写操作在重规划阶段不重放:失败的写已经可能落地了
            StepOutcome previous = outcomes.get(step.id());
            if (previous != null && previous.status() == StepOutcome.Status.FAILED
                    && registry.requireSpec(step.tool()).writeOperation()
                    && !registry.requireSpec(step.tool()).idempotent()) {
                emit(sink, ctx, "step_skip", data("stepId", step.id(), "tool", step.tool(),
                        "reason", "非幂等写操作不重放"));
                continue;
            }

            emit(sink, ctx, "step_start", data("stepId", step.id(), "tool", step.tool(),
                    "title", step.title(), "dependsOn", step.dependsOn(), "covers", step.covers()));
            StepOutcome outcome = executor.run(step, outputs, ctx, runPolicy);
            outcomes.put(step.id(), outcome);
            if (outcome.ok()) {
                outputs.put(step.id(), outcome.output());
                done.add(step.id());
                emit(sink, ctx, "step_ok", data("stepId", step.id(), "tool", step.tool(),
                        "ms", outcome.durationMs()));
            } else {
                emit(sink, ctx, "step_fail", data("stepId", step.id(), "tool", step.tool(),
                        "error", String.valueOf(outcome.error())));
            }
        }
    }

    private String buildAnswer(TaskSpec task, Plan plan, List<StepOutcome> outcomes,
                               boolean success, Set<String> reasons) {
        if (!success) {
            return "没完成:" + String.join("；", reasons);
        }
        StringBuilder sb = new StringBuilder("已完成:");
        for (StepOutcome o : outcomes) {
            if (o.ok()) {
                sb.append(stepLabel(o)).append("、");
            }
        }
        if (sb.charAt(sb.length() - 1) == '、') {
            sb.setLength(sb.length() - 1);
        }
        // 把退款金额和单号写进答案,这是用户真正关心的东西
        outcomes.stream().filter(o -> o.tool().equals("refund.submit") && o.ok())
                .findFirst().ifPresent(o -> sb.append(",退款单号 ").append(o.output().get("refundId"))
                        .append(" 金额 ¥").append(o.output().get("amount")));
        return sb.toString();
    }

    private String stepLabel(StepOutcome o) {
        return switch (o.tool()) {
            case "order.query" -> "查订单";
            case "logistics.track" -> "查物流";
            case "inventory.check" -> "查库存";
            case "refund.quote" -> "算可退金额";
            case "refund.submit" -> "提交退款";
            case "ticket.create" -> "建工单";
            default -> o.tool();
        };
    }

    private void emitPlan(EventSink sink, ExecutionContext ctx, Plan plan) {
        List<Map<String, Object>> steps = plan.steps().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", s.id());
            m.put("tool", s.tool());
            m.put("title", s.title());
            m.put("dependsOn", s.dependsOn());
            m.put("covers", s.covers());
            m.put("produces", s.produces());
            m.put("args", s.args().toString());
            m.put("write", registry.requireSpec(s.tool()).writeOperation());
            return m;
        }).toList();
        emit(sink, ctx, "plan", data("steps", steps, "count", steps.size()));
    }

    private void emit(EventSink sink, ExecutionContext ctx, String type, Map<String, Object> payload) {
        if (sink != null) {
            sink.emit(AgentEvent.of(type, ctx.elapsedMs(), payload));
        }
    }

    private Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
