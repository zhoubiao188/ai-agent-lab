package com.example.multiagent.coverage;

import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.Slot;
import com.example.multiagent.plan.Step;
import com.example.multiagent.plan.StepTemplates;
import com.example.multiagent.tasks.TaskSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 意图覆盖核对 —— 也就是"任务拆漏了没有"。
 *
 * <p>这是三类缺陷里最容易被漏检的一类,因为<b>拆漏的计划本身是自洽的</b>:
 * 每个步骤都有依赖、有产出、参数能解析、执行全绿。唯一的问题是它少做了一件事。
 * 靠执行期指标永远发现不了,只能拿"用户要什么"和"计划做什么"对一次账。</p>
 *
 * <p>覆盖核对之所以可行,是因为计划里每个步骤都声明了 {@code covers}。
 * 这个声明是模型的自我承诺,而核对是拿承诺和金标准对账 —— 承诺可以和事实不符,
 * 但"没承诺任何槽位"这件事本身就足够可疑了。</p>
 */
@Component
public class IntentCoverage {

    public CoverageReport check(Plan plan, TaskSpec task) {
        Set<String> covered = new LinkedHashSet<>();
        for (Step step : plan.steps()) {
            covered.addAll(step.covers());
        }
        Set<String> missing = new LinkedHashSet<>(task.slots());
        missing.removeAll(covered);
        return new CoverageReport(task.slots(), covered, missing);
    }

    /**
     * 机械补步:把漏掉的槽位补成标准步骤。
     *
     * <p>注意这里<b>没有调用模型</b>。漏项之所以能被机械修好,是因为槽位和步骤是一一对应的,
     * 补一个步骤不需要"重新想一遍整体计划"。这一点很关键:三类缺陷的修复成本完全不同,
     * 补漏最便宜,改顺序最贵 —— 后者必须重新规划,因为依赖关系是全局的。</p>
     *
     * <p>补出来的步骤天然接得上:它要的数据都来自<b>已经在计划里</b>的上游步骤,
     * 所以直接追加到末尾仍然是合法的拓扑序。</p>
     */
    public Plan patch(Plan plan, TaskSpec task, CoverageReport report) {
        Plan next = plan;
        int seq = plan.size();
        for (String slotKey : report.missing()) {
            Slot slot = Slot.of(slotKey);
            Step step = StepTemplates.build(next, task, slot, "s" + (++seq));
            next = next.plus(step);
        }
        return next;
    }
}
