package com.example.memory.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个可被模型调用的工具。实现类通过 Spring 容器自动注册到 {@link ToolRegistry}。
 */
public interface AgentTool {

    /** 工具名,需满足 [a-zA-Z0-9_-]{1,64},会原样发给模型 */
    String name();

    /** 给模型看的能力描述,决定模型会不会正确调用它,务必写清楚何时用、参数含义 */
    String description();

    /** JSON Schema 形式的入参定义 */
    JsonNode parameterSchema();

    /** 是否具备副作用(写操作)。有副作用的工具在演示/沙箱模式下可以被禁用 */
    default boolean mutating() {
        return false;
    }

    /**
     * 执行工具。
     *
     * @param args 已校验的入参(可能为空对象)
     * @param ctx  本次调用的上下文(会话 id、工具调用 id 等)
     */
    ToolResult execute(JsonNode args, ToolContext ctx);
}
