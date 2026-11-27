package com.example.multiagent.planner;

import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.Slot;
import com.example.multiagent.plan.Step;
import com.example.multiagent.plan.StepTemplates;
import com.example.multiagent.tasks.TaskSpec;
import org.springframework.stereotype.Component;

/**
 * 确定性规划器,替代真实模型。
 *
 * <p>为什么评测里不用真模型:计划缺陷必须<b>可复现</b>,否则"成功率从 40% 提到 95%"
 * 这句话就没法验证 —— 你甚至不知道两次跑的是不是同一批错误。这和 Day04 用 seed 派生
 * 故障注入是同一个取舍:不猜模型怎么错,而是把见过的错做成可以按开关注入的夹具。</p>
 *
 * <p>真实模型只要能把任务拆成 {@code slot} 列表,就能接进来:Planner 接口只要求返回 Plan。</p>
 */
@Component
public class RulePlanner implements Planner {

    @Override
    public Plan plan(TaskSpec task) {
        Plan plan = Plan.empty();
        int seq = 0;
        for (String key : task.slots()) {
            Step step = StepTemplates.build(plan, task, Slot.of(key), "s" + (++seq));
            plan = plan.plus(step);
        }
        return plan;
    }
}
