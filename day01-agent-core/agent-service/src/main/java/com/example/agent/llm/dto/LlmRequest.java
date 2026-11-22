package com.example.agent.llm.dto;

import java.util.List;

/** 一次 LLM 调用请求。 */
public record LlmRequest(
        String model,
        List<Msg> messages,
        List<ToolSpec> tools,
        double temperature
) {
}
