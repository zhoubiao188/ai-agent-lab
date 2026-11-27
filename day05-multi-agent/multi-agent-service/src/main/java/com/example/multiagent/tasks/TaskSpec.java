package com.example.multiagent.tasks;

import com.example.multiagent.plan.PlanFlaw;

import java.util.List;

/**
 * 一个评测任务 + 它的<b>金标准</b>。
 *
 * <p>{@code slots} 是这个任务必须覆盖的意图槽位,顺序即"正确的数据流顺序"。
 * 金标准不在模型手里,只在评测器手里 —— 这是整套方法的前提:
 * 你只有先能写出"什么算对",才谈得上判断"计划错没错"。</p>
 */
public record TaskSpec(
        String id,
        String request,
        String orderId,
        String sku,
        int quantity,
        String reason,
        List<String> slots,
        /** 评测夹具:固定注入的缺陷,null 表示这份计划是对的 */
        PlanFlaw forcedFlaw
) {

    public TaskSpec {
        slots = List.copyOf(slots);
    }

    public static TaskSpec of(String id, String request, String orderId, String sku, int quantity,
                              String reason, List<String> slots, PlanFlaw flaw) {
        return new TaskSpec(id, request, orderId, sku, quantity, reason, slots, flaw);
    }

    public boolean hasFlaw() {
        return forcedFlaw != null;
    }
}
