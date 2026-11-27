package com.example.agent.tool;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
    ToolResult call(Map<String, Object> args, ToolCallContext ctx);
}
