package com.example.memory.tool;

/** 工具执行上下文。 */
public record ToolContext(String conversationId, String toolCallId, String userId) {
}
