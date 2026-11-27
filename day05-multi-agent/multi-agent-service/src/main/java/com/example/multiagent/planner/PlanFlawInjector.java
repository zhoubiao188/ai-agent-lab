package com.example.multiagent.planner;

import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.PlanFlaw;
import com.example.multiagent.plan.Step;
import com.example.multiagent.spec.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划缺陷注入器 —— 这套方法的核心夹具。
 *
 * <p>它做的事很朴素:拿一份正确的计划,按指定类型破坏一处,使它变成"模型可能写出来的那种错"。
 * 三类破坏各自对应一种真实事故:</p>
 *
 * <p>1、<b>拆漏</b> —— 删掉一个没人依赖的尾部步骤。用户说了三件事,模型只做了两件,
 * 而且它自己不知道少了一件。这种错在执行阶段<b>零异常</b>。</p>
 *
 * <p>2、<b>拆错</b> —— 把写操作的 {@code $s2.amount} 换成字面量 {@code 199},
 * 同时断开依赖。这不是"顺序反了"的学术形态,这是我见过的真实事故形态:
 * 模型拿不到报价,于是<b>自己编了一个金额</b>,退款接口照样返回 200。</p>
 *
 * <p>3、<b>拆多</b> —— 复制一个读步骤。结果是对的,只是多花一次调用。
 * 它最无害,也最容易被忽略,所以最值得量化:一次多余调用 = 一次额外延迟 + 一次额外失败概率。</p>
 */
@Component
public class PlanFlawInjector {

    private final ToolRegistry registry;

    public PlanFlawInjector(ToolRegistry registry) {
        this.registry = registry;
    }

    public Plan inject(Plan clean, PlanFlaw flaw) {
        if (flaw == null) {
            return clean;
        }
        return switch (flaw) {
            case UNDER_COVER -> dropTail(clean);
            case WRONG_ORDER -> groundLiterals(clean);
            case OVER_SPLIT -> duplicateRead(clean);
        };
    }

    /** 拆漏:删掉最后一个"没人依赖"的步骤 */
    private Plan dropTail(Plan plan) {
        List<Step> steps = plan.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step candidate = steps.get(i);
            boolean referenced = steps.stream()
                    .anyMatch(other -> other.dependsOn().contains(candidate.id())
                            || other.refs().stream().anyMatch(r -> r.stepId().equals(candidate.id())));
            if (!referenced) {
                return plan.remove(candidate.id());
            }
        }
        return plan;
    }

    /** 拆错:写操作的关键参数换成模型自己编的字面量,依赖断开 */
    private Plan groundLiterals(Plan plan) {
        for (int i = plan.steps().size() - 1; i >= 0; i--) {
            Step step = plan.steps().get(i);
            if (!registry.requireSpec(step.tool()).writeOperation()) {
                continue;
            }
            if (step.refs().isEmpty()) {
                continue;
            }
            ObjectNode args = step.args().deepCopy();
            List<String> fields = new ArrayList<>();
            args.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode v = args.get(field);
                if (v.isTextual() && v.asText().startsWith("$")) {
                    args.set(field, literalFor(field));
                }
            }
            return plan.replace(step.withArgs(args).withDependsOn(List.of()));
        }
        return plan;
    }

    /** 拆多:复制第一个读步骤 */
    private Plan duplicateRead(Plan plan) {
        for (Step step : plan.steps()) {
            if (registry.requireSpec(step.tool()).writeOperation()) {
                continue;
            }
            String newId = "s" + (plan.size() + 1);
            Step copy = new Step(newId, step.title() + "(重算)", step.tool(), step.args().deepCopy(),
                    step.dependsOn(), step.covers(), step.produces(), step.consumes());
            return plan.plus(copy);
        }
        return plan;
    }

    private JsonNode literalFor(String field) {
        ObjectNode holder = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (field.toLowerCase().contains("amount")) {
            holder.put(field, 199.0);
        } else {
            holder.put(field, "Q00000");
        }
        return holder.get(field);
    }
}
