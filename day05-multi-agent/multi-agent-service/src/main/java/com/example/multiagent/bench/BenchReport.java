package com.example.multiagent.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 四组配置在同一批任务上的对照结果。 */
public record BenchReport(
        long seed,
        int taskCount,
        List<Row> rows,
        List<TaskDetail> details
) {

    /**
     * @param success      金标准判定的成功率
     * @param falseSuccess 系统宣称完成、但金标准不满足的"假成功"
     * @param blocked      连执行都没执行就被拦下的任务数
     * @param avgSteps     平均真实执行步数
     */
    public record Row(
            String policy,
            int total,
            int success,
            int falseSuccess,
            int blocked,
            double successRate,
            double avgSteps,
            long avgMs,
            long p95Ms,
            int replans,
            Map<String, Integer> failureKinds
    ) {
    }

    /** 逐任务的明细,前端用它画"哪一条被谁救回来了" */
    public record TaskDetail(
            String taskId,
            String request,
            String flaw,
            Map<String, String> byPolicy
    ) {
        public TaskDetail {
            byPolicy = new LinkedHashMap<>(byPolicy);
        }
    }
}
