package com.example.agent.pipeline;

import com.example.o11y.redact.Redactor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 计划中的一步。args 是渲染后的最终入参,进 Span 只放摘要和哈希。 */
public record PlannedStep(int index, String tool, Map<String, Object> args, String dependsOn) {

    public String argSummary() {
        if (args.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        args.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            String value = String.valueOf(v);
            sb.append(k).append('=').append(value.length() > 40 ? value.substring(0, 40) + "…" : value);
        });
        return sb.append('}').toString();
    }

    /** 入参哈希。幂等键、跨次比对都靠它,不能只截前 8 位字符串。 */
    public String argHash() {
        Redactor redactor = new Redactor();
        Map<String, Object> sorted = new LinkedHashMap<>(new java.util.TreeMap<>(args));
        return redactor.fingerprint(sorted.toString());
    }
}
