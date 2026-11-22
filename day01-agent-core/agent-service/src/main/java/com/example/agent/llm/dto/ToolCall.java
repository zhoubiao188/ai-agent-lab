package com.example.agent.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 一次工具调用(assistant 发起,后端执行)。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(String id, String name, String arguments) {
}
