package com.example.agent.fault;

import com.example.agent.tool.ErrorKind;

/**
 * 剧本编译:把"哪个场景"变成"具体在哪失败几次"。
 *
 * <p>故意让故障参数与 seed 有关:同一个剧本在不同 seed 下的退避时间、片段条数略有差异,
 * 避免评测集里 200 条样本长得一模一样,排序算法"记住了答案"。</p>
 */
public final class FaultInjector {

    private final long seed;

    public FaultInjector(long seed) {
        this.seed = seed;
    }

    public FaultPlan plan(Scenario scenario, String taskId) {
        long s = seed ^ Math.abs((taskId == null ? "na" : taskId).hashCode());
        return switch (scenario) {
            case NORMAL -> FaultPlan.normal();
            case SLOW_TOOL -> new FaultPlan(scenario, "order.get", 900 + Math.floorMod(s, 4) * 50,
                    null, 0, null, false, false, false);
            case RETRY_STORM -> new FaultPlan(scenario, null, 0,
                    "inventory.get", 2, ErrorKind.UPSTREAM_5XX, false, false, false);
            case SILENT_FAILURE -> new FaultPlan(scenario, null, 0,
                    null, 0, null, false, false, true);
            case COST_BLOWUP -> new FaultPlan(scenario, null, 0,
                    null, 0, null, false, true, false);
            case ARG_DRIFT -> new FaultPlan(scenario, null, 0,
                    null, 0, null, true, false, false);
        };
    }
}
