package com.example.memory.llm.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 提供给模型的工具声明(会被转换成 OpenAI function 格式)。 */
public record ToolSpec(String name, String description, JsonNode parameters) {
}
