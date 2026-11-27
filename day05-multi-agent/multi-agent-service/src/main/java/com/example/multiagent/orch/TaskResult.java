package com.example.multiagent.orch;

/** 一次任务的最终结果。{@code claimed} 是"系统自己认为成功"(所有步骤都绿),{@code success} 是"金标准判定成功"。 */
public record TaskResult(
        String taskId,
        String policyName,
        String planSource,
        boolean success,
        boolean claimed,
        java.util.Set<String> reasons,
        com.example.multiagent.plan.Plan plan,
        java.util.List<StepOutcome> outcomes,
        com.example.multiagent.critic.Critique critique,
        int replans,
        long durationMs,
        int executedSteps
) {

    /** 系统宣称完成、但金标准不满足 —— 这类"假成功"比失败危险得多 */
    public boolean falseSuccess() {
        return claimed && !success;
    }
}
