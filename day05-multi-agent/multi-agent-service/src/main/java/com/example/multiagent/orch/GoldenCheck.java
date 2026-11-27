package com.example.multiagent.orch;

import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.Step;
import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 金标准判定 —— 站在<b>用户</b>那一侧验收,而不是站在"步骤返回码"那一侧。
 *
 * <p>这是全文最重要的一个函数,因为它定义了什么叫"成功"。如果成功等于"所有步骤都 200",
 * 那么一个漏做退款、金额还写错的计划,也会被判为成功。线上绝大多数"Agent 明明跑完了,
 * 用户却说不对"的事故,根子都在这个判定标准上。</p>
 */
@Component
public class GoldenCheck {

    public record Judgement(boolean success, Set<String> reasons) {
    }

    public Judgement judge(TaskSpec task, Plan plan, List<StepOutcome> outcomes) {
        Set<String> reasons = new LinkedHashSet<>();
        Map<String, Step> steps = plan.steps().stream()
                .collect(Collectors.toMap(Step::id, Function.identity(), (a, b) -> a));

        Set<String> covered = new LinkedHashSet<>();
        for (StepOutcome o : outcomes) {
            Step step = steps.get(o.stepId());
            if (o.ok() && step != null) {
                covered.addAll(step.covers());
            }
        }

        Set<String> missing = new LinkedHashSet<>(task.slots());
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
            reasons.add("UNDER_COVER:" + missing);
        }

        // 写操作验收:必须真落地,且金额必须来自报价
        if (task.slots().contains("refund")) {
            StepOutcome refund = outcomes.stream()
                    .filter(o -> o.tool().equals("refund.submit"))
                    .reduce((a, b) -> b).orElse(null);
            if (refund == null || !refund.ok()) {
                reasons.add("WRITE_MISSING:refund.submit");
            } else {
                if (refund.consumedRefs().isEmpty()) {
                    reasons.add("WRITE_UNGROUNDED:refund.submit 金额未溯源");
                }
                JsonNode quoted = outcomes.stream()
                        .filter(o -> o.tool().equals("refund.quote") && o.ok())
                        .map(o -> o.output().get("amount"))
                        .findFirst().orElse(null);
                JsonNode paid = refund.output().get("amount");
                if (quoted != null && paid != null && Math.abs(quoted.asDouble() - paid.asDouble()) > 0.01) {
                    reasons.add("AMOUNT_MISMATCH:报价 " + quoted + " 实退 " + paid);
                }
            }
        }

        // 必需步骤失败(只算承担了诉求的那些步骤,冗余步骤失败不背这个锅)
        for (StepOutcome o : outcomes) {
            Step step = steps.get(o.stepId());
            if (o.status() == StepOutcome.Status.FAILED && step != null
                    && !step.covers().isEmpty() && !java.util.Collections.disjoint(step.covers(), task.slots())) {
                reasons.add("STEP_FAILED:" + o.stepId() + "(" + o.tool() + ")");
            }
        }

        return new Judgement(reasons.isEmpty(), reasons);
    }
}
