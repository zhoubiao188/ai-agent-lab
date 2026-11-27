package com.example.multiagent.critic;

import com.example.multiagent.orch.StepOutcome;
import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.Step;
import com.example.multiagent.spec.ToolRegistry;
import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行后复核。它检查的不是"步骤成功了吗",而是<b>"计划的承诺兑现了吗"</b>。
 *
 * <p>四件事:</p>
 * <p>1、<b>覆盖</b> —— 成功执行的步骤,是不是把用户要的都干了。注意要按"成功的步骤"算,
 * 因为计划里有不代表执行了。</p>
 * <p>2、<b>产出</b> —— 步骤声明产出 {@code paidAmount},结果里真有这个字段吗。
 * 模型很喜欢在 produces 里写一堆漂亮字段,实际只返回一个 id。</p>
 * <p>3、<b>写操作是否落地</b> —— 承诺退款,就必须有一条 refund.submit 的成功记录。</p>
 * <p>4、<b>溯源</b> —— 写操作的金额是算出来的,还是编出来的。这条只有拿计划和执行记录对账才能看出来。</p>
 *
 * <p><b>它查不出"拆多"</b>:多调一次读接口,结果照样正确。绕远路只有静态校验能发现。
 * 这也是为什么两层都要有 —— 静态看形状,动态看兑现。</p>
 */
@Component
public class Critic {

    private final ToolRegistry registry;

    public Critic(ToolRegistry registry) {
        this.registry = registry;
    }

    public Critique review(Plan plan, TaskSpec task, List<StepOutcome> outcomes) {
        Map<String, Step> steps = plan.steps().stream()
                .collect(Collectors.toMap(Step::id, Function.identity(), (a, b) -> a));
        Map<String, StepOutcome> byId = outcomes.stream()
                .collect(Collectors.toMap(StepOutcome::stepId, Function.identity(), (a, b) -> b));

        List<String> findings = new ArrayList<>();
        Set<String> missingOutputs = new LinkedHashSet<>();
        Set<String> ungrounded = new LinkedHashSet<>();
        Set<String> failedWrites = new LinkedHashSet<>();

        // 1. 覆盖:只认成功执行的步骤
        Set<String> covered = new LinkedHashSet<>();
        for (StepOutcome o : outcomes) {
            Step step = steps.get(o.stepId());
            if (o.ok() && step != null) {
                covered.addAll(step.covers());
            }
        }
        Set<String> missingSlots = new LinkedHashSet<>(task.slots());
        missingSlots.removeAll(covered);
        if (!missingSlots.isEmpty()) {
            findings.add("诉求未覆盖: " + missingSlots);
        }

        // 2. 产出兑现
        for (StepOutcome o : outcomes) {
            Step step = steps.get(o.stepId());
            if (!o.ok() || step == null || o.output() == null) {
                continue;
            }
            for (String field : step.produces()) {
                JsonNode v = o.output().get(field);
                if (v == null || v.isNull()) {
                    missingOutputs.add(step.id() + "." + field);
                }
            }
        }
        if (!missingOutputs.isEmpty()) {
            findings.add("承诺产出未兑现: " + missingOutputs);
        }

        // 3 + 4. 写操作落地与溯源
        for (Step step : plan.steps()) {
            if (!registry.requireSpec(step.tool()).writeOperation()) {
                continue;
            }
            StepOutcome o = byId.get(step.id());
            if (o == null || !o.ok()) {
                failedWrites.add(step.id() + "(" + step.tool() + ")");
                continue;
            }
            if (!step.refs().isEmpty() && o.consumedRefs().isEmpty()) {
                ungrounded.add(step.id() + "(" + step.tool() + ")");
            }
        }
        if (!failedWrites.isEmpty()) {
            findings.add("写操作未落地: " + failedWrites);
        }
        if (!ungrounded.isEmpty()) {
            findings.add("写操作参数未溯源: " + ungrounded);
        }

        Verdict verdict;
        if (!failedWrites.isEmpty()) {
            // 写失败不能重规划重试:重规划会再发一次写请求,那是重复退款
            verdict = Verdict.FAIL;
        } else if (!ungrounded.isEmpty() || !missingOutputs.isEmpty()) {
            verdict = Verdict.REPLAN;
        } else if (!missingSlots.isEmpty()) {
            verdict = Verdict.PATCH;
        } else {
            verdict = Verdict.PASS;
        }
        return new Critique(verdict, findings, missingSlots, ungrounded, missingOutputs, failedWrites);
    }
}
