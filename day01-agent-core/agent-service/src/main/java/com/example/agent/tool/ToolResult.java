package com.example.agent.tool;

/**
 * 工具执行结果。{@code content} 会被序列化成字符串喂回模型,
 * 因此要保持短、结构化、语义清晰,不要塞原始 HTML。
 */
public record ToolResult(boolean success, String content, long elapsedMs) {

    public static ToolResult ok(String content, long elapsedMs) {
        return new ToolResult(true, content, elapsedMs);
    }

    public static ToolResult fail(String reason, long elapsedMs) {
        return new ToolResult(false, "ERROR: " + reason, elapsedMs);
    }
}
