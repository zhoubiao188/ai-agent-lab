package com.example.tool.exec;

import com.example.tool.repair.Repair;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次工具调用的最终结果。除了成败,还带"怎么走到这一步的"——重试了几次、每次错什么、
 * 参数改了什么、是不是降级来的。这些信息在排障时比结果本身更有用,
 * 也是前端时间线能把"重试"画成一个可解释的段落的唯一依据。
 */
public record ToolResult(
        String callId,
        String tool,
        Status status,
        JsonNode payload,
        String errorKind,
        String error,
        int attempts,
        long startMs,
        long endMs,
        List<Attempt> attemptLog,
        List<Repair> repairs,
        boolean fallback,
        boolean circuitRejected
) {

    public enum Status {OK, FAILED, SKIPPED}

    /** 单次尝试。delayBeforeMs 是这次尝试前等待的退避时间 */
    public record Attempt(int index, long startMs, long endMs, boolean ok,
                          String errorKind, String error, long delayBeforeMs) {
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    public static ToolResult ok(String callId, String tool, JsonNode payload, int attempts,
                                long startMs, long endMs, List<Attempt> log, List<Repair> repairs) {
        return new ToolResult(callId, tool, Status.OK, payload, null, null, attempts,
                startMs, endMs, log, repairs, false, false);
    }

    public static ToolResult failed(String callId, String tool, ErrorKind kind, String error,
                                    int attempts, long startMs, long endMs,
                                    List<Attempt> log, List<Repair> repairs, boolean circuitRejected) {
        return new ToolResult(callId, tool, Status.FAILED, null, kind.name(), error, attempts,
                startMs, endMs, log, repairs, false, circuitRejected);
    }

    public static ToolResult skipped(String callId, String tool, String reason, long at) {
        return new ToolResult(callId, tool, Status.SKIPPED, null, "DEPENDENCY_FAILED", reason, 0,
                at, at, List.of(), List.of(), false, false);
    }

    public ToolResult asFallback(JsonNode fallbackPayload) {
        return new ToolResult(callId, tool, Status.OK, fallbackPayload, errorKind, error, attempts,
                startMs, endMs, attemptLog, repairs, true, circuitRejected);
    }
}
