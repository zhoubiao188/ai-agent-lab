package com.example.multiagent.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一份任务计划:有向无环图,节点是步骤,边是数据依赖。
 *
 * <p>为什么非要 DAG 而不是"步骤列表"?因为列表只能表达"先后",不能表达"可以并行";
 * 更重要的是,<b>列表没有环的概念</b>,而模型给出的计划真的会出现 A 依赖 B、B 又依赖 A。
 * 用列表存,这种错误永远发现不了;用图存,一次拓扑排序就暴露了。</p>
 */
public record Plan(List<Step> steps) {

    public Plan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static Plan empty() {
        return new Plan(List.of());
    }

    public static Plan of(Step... steps) {
        return new Plan(List.of(steps));
    }

    public Plan plus(Step step) {
        List<Step> next = new ArrayList<>(steps);
        next.add(step);
        return new Plan(next);
    }

    public Plan replace(Step step) {
        List<Step> next = new ArrayList<>();
        for (Step s : steps) {
            next.add(s.id().equals(step.id()) ? step : s);
        }
        return new Plan(next);
    }

    public Plan remove(String id) {
        return new Plan(steps.stream().filter(s -> !s.id().equals(id)).toList());
    }

    public Plan removeAll(Collection<String> ids) {
        return new Plan(steps.stream().filter(s -> !ids.contains(s.id())).toList());
    }

    public Optional<Step> find(String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public List<String> ids() {
        return steps.stream().map(Step::id).toList();
    }

    public int size() {
        return steps.size();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** 计划里所有被声明覆盖的意图槽位 */
    public Set<String> coveredSlots() {
        Set<String> out = new LinkedHashSet<>();
        steps.forEach(s -> out.addAll(s.covers()));
        return out;
    }

    /** 计划里所有被声明产出的字段 */
    public Set<String> producedFields() {
        Set<String> out = new LinkedHashSet<>();
        steps.forEach(s -> out.addAll(s.produces()));
        return out;
    }

    /**
     * 拓扑排序。有环就返回 empty —— 调用方必须显式处理"计划不可执行",
     * 而不是拿一个被截断的顺序硬跑。
     */
    public Optional<List<Step>> topological() {
        Set<String> ids = new LinkedHashSet<>(ids());
        Set<String> done = new LinkedHashSet<>();
        List<Step> order = new ArrayList<>();
        boolean progressed = true;
        while (order.size() < steps.size() && progressed) {
            progressed = false;
            for (Step s : steps) {
                if (done.contains(s.id())) {
                    continue;
                }
                if (ids.containsAll(s.dependsOn()) && done.containsAll(s.dependsOn())
                        && dependentRefsResolved(s, done)) {
                    order.add(s);
                    done.add(s.id());
                    progressed = true;
                }
            }
        }
        return order.size() == steps.size() ? Optional.of(order) : Optional.empty();
    }

    /** 参数里引用的其他步骤必须已经执行完,否则这一轮不能跑(数据还没到) */
    private boolean dependentRefsResolved(Step step, Set<String> done) {
        for (Refs.Ref ref : step.refs()) {
            if (!ref.stepId().equals(step.id()) && !done.contains(ref.stepId())) {
                return false;
            }
        }
        return true;
    }

    /** 计划的内容指纹,用来判断"重规划"到底有没有改动计划 */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        steps.forEach(s -> sb.append(s.id()).append('|').append(s.tool()).append('|')
                .append(s.dependsOn()).append('|').append(s.args()).append('\n'));
        return sb.toString();
    }
}
