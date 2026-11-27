package com.example.agent.pipeline;

import com.example.agent.tool.ErrorKind;

import java.util.Map;

/** 单步执行结果。fallback=true 表示用的是降级数据,业务上"成功",数据上是旧的。 */
public record StepOutcome(boolean ok, Map<String, Object> data, String summary,
                          ErrorKind error, int attempts, boolean fallback) {

    public static StepOutcome ok(Map<String, Object> data, String summary, int attempts) {
        return new StepOutcome(true, data, summary, null, attempts, false);
    }

    public static StepOutcome degraded(Map<String, Object> data, String summary, int attempts) {
        return new StepOutcome(true, data, summary, null, attempts, true);
    }

    public static StepOutcome failed(ErrorKind error, int attempts) {
        return new StepOutcome(false, Map.of(), "", error, attempts, false);
    }
}
