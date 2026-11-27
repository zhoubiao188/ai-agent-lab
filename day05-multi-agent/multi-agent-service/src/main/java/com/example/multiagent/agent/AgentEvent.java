package com.example.multiagent.agent;

import java.util.Map;

/**
 * 推给前端的一条事件。时间戳用"相对任务开始的毫秒数",前端不用做时钟对齐。
 */
public record AgentEvent(String type, long t, Map<String, Object> data) {

    public static AgentEvent of(String type, long t, Map<String, Object> data) {
        return new AgentEvent(type, t, data);
    }
}
