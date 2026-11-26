package com.example.tool.api.dto;

import com.example.tool.exec.RunPolicy;

/**
 * 一次实时任务的入参。policy 用短名字是为了让前端更好传,也方便直接 curl 调。
 */
public record AgentRunRequest(String task, String policy, Long seed, Boolean faults) {

    public String taskOrDefault() {
        return task == null || task.isBlank()
                ? "处理订单 A10003 的退款申请,并跟进物流异常"
                : task;
    }

    public RunPolicy runPolicy() {
        if (policy == null) {
            return RunPolicy.FULL;
        }
        return switch (policy.toLowerCase()) {
            case "l0", "baseline" -> RunPolicy.BASELINE;
            case "l1", "retry" -> RunPolicy.RETRY;
            case "l2", "repair" -> RunPolicy.RETRY_REPAIR;
            default -> RunPolicy.FULL;
        };
    }
}
