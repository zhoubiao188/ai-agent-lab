package com.example.multiagent.plan;

import com.example.multiagent.spec.ToolRegistry;
import com.example.multiagent.spec.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 计划静态校验:在<b>花掉一次 tool 调用之前</b>把结构错误拦住。
 *
 * <p>九条规则,按"能不能不执行就判死"排序。真正有杀伤力的是后三条:</p>
 * <ul>
 *   <li><b>MISSING_DEPENDENCY</b> —— 参数里引用了 {@code $s1.paidAmount},但 s1 不在 dependsOn 里。
 *       模型经常这么干:它知道要用订单金额,于是写了一堆引用,却没意识到"s1 已经跑到 s4 后面去了"。</li>
 *   <li><b>REDUNDANT_STEP</b> —— 同一工具、同一参数出现两次。多花的不是一次调用,是一次延迟 + 一次失败概率。</li>
 *   <li><b>UNGROUNDED_WRITE</b> —— 退款金额、报价单号是写死的字面量。
 *       这是最贵的一种错:它会真的退出去一笔钱,而且每一步的返回码都是 200。</li>
 * </ul>
 */
@Component
public class PlanValidator {

    /** 这些参数"只有上游算得出来",模型写死就一定是编的 */
    static final Set<String> DERIVED_ARGS = Set.of("amount", "quoteId", "paidAmount", "refundedAmount", "refundId");

    private final ToolRegistry registry;
    private final int maxSteps;

    public PlanValidator(ToolRegistry registry, com.example.multiagent.config.CoordProperties props) {
        this.registry = registry;
        this.maxSteps = props.maxSteps();
    }

    public List<PlanViolation> validate(Plan plan) {
        List<PlanViolation> out = new ArrayList<>();
        if (plan.isEmpty()) {
            out.add(PlanViolation.of(ViolationCode.TOO_MANY_STEPS, null, "计划为空"));
            return out;
        }
        if (plan.size() > maxSteps) {
            out.add(PlanViolation.of(ViolationCode.TOO_MANY_STEPS, null,
                    "步骤数 " + plan.size() + " 超过上限 " + maxSteps));
        }

        Set<String> seen = new HashSet<>();
        for (Step step : plan.steps()) {
            if (!seen.add(step.id())) {
                out.add(PlanViolation.of(ViolationCode.DUPLICATE_ID, step.id(), "步骤 id 重复"));
            }
        }

        Map<String, Step> byId = new java.util.LinkedHashMap<>();
        plan.steps().forEach(s -> byId.put(s.id(), s));

        for (Step step : plan.steps()) {
            Optional<ToolSpec> spec = registry.spec(step.tool());
            if (spec.isEmpty()) {
                out.add(PlanViolation.of(ViolationCode.UNKNOWN_TOOL, step.id(), step.tool()));
                continue;
            }
            // 依赖必须存在
            for (String dep : step.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    out.add(PlanViolation.of(ViolationCode.DANGLING_DEPENDENCY, step.id(),
                            "依赖 " + dep + " 不在计划里"));
                }
            }
            // 引用必须被声明为依赖,且字段必须有人产出
            for (Refs.Ref ref : step.refs()) {
                Step source = byId.get(ref.stepId());
                if (source == null) {
                    out.add(PlanViolation.of(ViolationCode.DANGLING_DEPENDENCY, step.id(),
                            "引用了不存在的步骤 " + ref));
                    continue;
                }
                if (!step.dependsOn().contains(ref.stepId())) {
                    out.add(PlanViolation.of(ViolationCode.MISSING_DEPENDENCY, step.id(),
                            "参数引用了 " + ref + ",但 dependsOn 里没有 " + ref.stepId()));
                }
                if (!source.produces().isEmpty() && !source.produces().contains(ref.field())) {
                    out.add(PlanViolation.of(ViolationCode.UNPRODUCED_REFERENCE, step.id(),
                            ref + " 的字段 " + ref.field() + " 不在 " + ref.stepId() + " 的产出声明里 "
                                    + source.produces()));
                }
            }
            // 写操作的关键参数必须能溯源
            if (spec.get().writeOperation()) {
                for (String arg : derivedArgsPresent(step)) {
                    JsonNode v = step.args().get(arg);
                    if (v != null && !v.isNull() && !isRef(v)) {
                        out.add(PlanViolation.of(ViolationCode.UNGROUNDED_WRITE, step.id(),
                                arg + " = " + v + " 是字面量,不是上游算出来的"));
                    }
                }
            }
        }

        // 环
        if (plan.topological().isEmpty()) {
            out.add(PlanViolation.of(ViolationCode.CYCLE, null, "拓扑排序失败,依赖成环"));
        }

        // 重复步骤:同工具 + 同参数
        Set<String> signatures = new LinkedHashSet<>();
        for (Step step : plan.steps()) {
            String sig = step.tool() + "|" + step.args();
            if (!signatures.add(sig)) {
                out.add(PlanViolation.of(ViolationCode.REDUNDANT_STEP, step.id(),
                        step.tool() + " 用同样的参数又调了一次"));
            }
        }

        return out;
    }

    private List<String> derivedArgsPresent(Step step) {
        List<String> out = new ArrayList<>();
        DERIVED_ARGS.forEach(a -> {
            if (step.args().has(a)) {
                out.add(a);
            }
        });
        return out;
    }

    private boolean isRef(JsonNode v) {
        return v.isTextual() && v.asText().startsWith("$");
    }
}
