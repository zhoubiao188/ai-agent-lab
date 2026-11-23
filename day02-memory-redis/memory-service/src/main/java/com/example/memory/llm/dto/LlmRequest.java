package com.example.memory.llm.dto;

import com.example.memory.domain.Msg;

import java.util.List;

/** 一次 LLM 调用请求。 */
public record LlmRequest(
        String model,
        List<Msg> messages,
        List<ToolSpec> tools,
        double temperature
) {
}
