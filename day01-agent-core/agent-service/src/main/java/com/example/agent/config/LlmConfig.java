package com.example.agent.config;

import com.example.agent.llm.LlmProvider;
import com.example.agent.llm.MockLlmProvider;
import com.example.agent.llm.OpenAiCompatLlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code agent.provider} 装配 provider。切换模型供应商不需要改任何代码。
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmProvider llmProvider(AgentProperties props, ObjectMapper mapper) {
        String which = props.getProvider() == null ? "mock" : props.getProvider().trim().toLowerCase();
        LlmProvider provider = switch (which) {
            case "openai", "deepseek", "ollama", "qwen", "moonshot" ->
                    new OpenAiCompatLlmProvider(props.getLlm(), mapper);
            case "mock" -> new MockLlmProvider(mapper);
            default -> throw new IllegalStateException("未知的 agent.provider: " + which);
        };
        log.info("LLM provider = {} (model={}, realModel={})",
                provider.name(), props.getLlm().getModel(), provider.realModel());
        return provider;
    }
}
