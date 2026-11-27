package com.example.multiagent.plan;

/** 一条计划校验问题。带 stepId 是为了让前端能直接把卡片标红,而不是丢一段文字给用户。 */
public record PlanViolation(ViolationCode code, String stepId, String detail) {

    public static PlanViolation of(ViolationCode code, String stepId, String detail) {
        return new PlanViolation(code, stepId, detail);
    }

    @Override
    public String toString() {
        return (stepId == null ? "-" : stepId) + " " + code.label() + ": " + detail;
    }
}
