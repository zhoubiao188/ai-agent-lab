package com.example.multiagent.critic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 复核报告。它不是一句"通过/不通过",而是<b>可执行的差异清单</b>:
 * 缺了哪个槽位、哪个字段承诺了没拿到、哪个写操作没溯源。
 * 重规划器拿的就是这份清单 —— 给模型的反馈越具体,它第二次改对的概率越高。
 */
public record Critique(
        Verdict verdict,
        List<String> findings,
        Set<String> uncoveredSlots,
        Set<String> ungroundedWrites,
        Set<String> missingOutputs,
        Set<String> failedSteps
) {

    public Critique {
        findings = List.copyOf(findings);
        uncoveredSlots = new LinkedHashSet<>(uncoveredSlots);
        ungroundedWrites = new LinkedHashSet<>(ungroundedWrites);
        missingOutputs = new LinkedHashSet<>(missingOutputs);
        failedSteps = new LinkedHashSet<>(failedSteps);
    }

    public boolean clean() {
        return verdict == Verdict.PASS;
    }

    /** 给重规划器/前端看的一句话摘要 */
    public String summary() {
        if (findings.isEmpty()) {
            return "计划与执行一致";
        }
        return String.join("；", findings);
    }
}
