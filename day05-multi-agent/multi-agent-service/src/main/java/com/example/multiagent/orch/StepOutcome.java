package com.example.multiagent.orch;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 一个步骤的执行结果。{@code consumedRefs} 记录它执行时是否真的接了上游数据 —— 这是事后溯源的关键证据。 */
public record StepOutcome(
        String stepId,
        String tool,
        Status status,
        long durationMs,
        String error,
        JsonNode output,
        List<String> consumedRefs
) {

    public enum Status {OK, FAILED, SKIPPED}

    public StepOutcome {
        consumedRefs = consumedRefs == null ? List.of() : List.copyOf(consumedRefs);
    }

    public static StepOutcome ok(String stepId, String tool, long ms, JsonNode output, List<String> refs) {
        return new StepOutcome(stepId, tool, Status.OK, ms, null, output, refs);
    }

    public static StepOutcome failed(String stepId, String tool, long ms, String error, List<String> refs) {
        return new StepOutcome(stepId, tool, Status.FAILED, ms, error, null, refs);
    }

    public static StepOutcome skipped(String stepId, String tool, String why) {
        return new StepOutcome(stepId, tool, Status.SKIPPED, 0, why, null, List.of());
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
