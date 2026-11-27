package com.example.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具返回。
 *
 * <p>{@code echo} 是给写操作用的:服务端把收到的关键字段原样回显。前端/Agent 侧拿它跟自己的入参
 * 比一遍,就能发现"接口说成功、但实际写进去的是另一回事"。这个字段不产生 error 状态,
 * 却能在半年后救你一次 —— 沉默失败基本都藏在这种地方。</p>
 */
public record ToolResult(Map<String, Object> data, Map<String, Object> echo,
                         String summary, boolean stale) {

    public static ToolResult of(Map<String, Object> data, String summary) {
        return new ToolResult(data, Map.of(), summary, false);
    }

    public static ToolResult write(Map<String, Object> data, Map<String, Object> echo, String summary) {
        return new ToolResult(data, echo, summary, false);
    }

    public static ToolResult stale(Map<String, Object> data, String summary) {
        return new ToolResult(data, Map.of(), summary, true);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> merge() {
        Map<String, Object> all = new LinkedHashMap<>(data);
        echo.forEach((k, v) -> all.put("echo." + k, v));
        return all;
    }

    public static final class Builder {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final Map<String, Object> echo = new LinkedHashMap<>();
        private String summary = "";
        private boolean stale;

        public Builder put(String key, Object value) {
            data.put(key, value);
            return this;
        }

        public Builder echo(String key, Object value) {
            echo.put(key, value);
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder stale(boolean stale) {
            this.stale = stale;
            return this;
        }

        public ToolResult build() {
            return new ToolResult(data, echo, summary, stale);
        }
    }
}
