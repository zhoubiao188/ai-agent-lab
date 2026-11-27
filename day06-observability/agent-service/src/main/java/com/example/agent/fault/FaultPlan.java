package com.example.agent.fault;

import com.example.agent.tool.ErrorKind;

/**
 * 一次运行里"哪里会出问题"的确定性描述。
 *
 * <p>不用随机数生成器逐次掷骰子,而是把剧本编译成一份计划:哪个工具慢、慢多久、错几次、错什么类型。
 * 好处是同一个 seed 的两次执行完全一致,评测结果可复现,也方便前端"重放"某一次事故。</p>
 */
public record FaultPlan(
        Scenario scenario,
        String slowTool,
        long slowMs,
        String flakyTool,
        int flakyTimes,
        ErrorKind flakyKind,
        boolean dropAmountSlot,
        boolean verboseDocs,
        boolean silentWrite) {

    public static FaultPlan normal() {
        return new FaultPlan(Scenario.NORMAL, null, 0, null, 0, null, false, false, false);
    }

    public boolean affectsSlow(String tool) {
        return slowTool != null && slowTool.equals(tool);
    }

    public boolean affectsFlaky(String tool) {
        return flakyTool != null && flakyTool.equals(tool);
    }

    /** 该工具是不是本剧本的埋点目标(评测标注用,不参与运行时判断)。 */
    public boolean isFaultTarget(String tool) {
        return affectsSlow(tool) || affectsFlaky(tool) || (silentWrite() && "refund.commit".equals(tool));
    }
}
