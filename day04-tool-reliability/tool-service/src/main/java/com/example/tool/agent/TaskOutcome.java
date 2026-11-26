package com.example.tool.agent;

import com.example.tool.exec.ToolResult;

import java.util.List;

/**
 * 一次任务的最终结果。除了答案,还带上可靠性相关的统计 —— 这些数字才是评测和线上看板要的。
 */
public record TaskOutcome(
        String requestId,
        String task,
        String answer,
        Status status,
        List<ToolResult> results,
        long wallMs,
        int callCount,
        int retries,
        int repairs,
        int fallbacks
) {

    public enum Status {
        /** 全部成功,没有降级 */
        SUCCESS,
        /** 拿到了结果,但有降级数据 */
        DEGRADED,
        FAILED
    }

    public boolean ok() {
        return status != Status.FAILED;
    }
}
