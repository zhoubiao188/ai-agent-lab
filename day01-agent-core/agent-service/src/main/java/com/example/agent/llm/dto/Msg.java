package com.example.agent.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 内部统一消息模型,与具体厂商的协议解耦。
 * <p>{@code role} 取值:system / user / assistant / tool</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Msg(
        String role,
        String content,
        /** tool 角色消息必须带上对应 tool_call 的 id */
        String toolCallId,
        /** tool 角色消息对应的工具名(部分厂商需要) */
        String name,
        /** assistant 角色消息中的工具调用列表 */
        List<ToolCall> toolCalls
) {

    public static Msg system(String content) {
        return new Msg("system", content, null, null, null);
    }

    public static Msg user(String content) {
        return new Msg("user", content, null, null, null);
    }

    public static Msg assistant(String content, List<ToolCall> toolCalls) {
        return new Msg("assistant", content, null, null, toolCalls);
    }

    public static Msg tool(String toolCallId, String name, String content) {
        return new Msg("tool", content, toolCallId, name, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
