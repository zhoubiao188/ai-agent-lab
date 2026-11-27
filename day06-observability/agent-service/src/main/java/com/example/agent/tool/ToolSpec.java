package com.example.agent.tool;

import java.util.List;

/**
 * 工具声明。
 *
 * <p>{@code baseLatencyMs} 不是凑数:它决定了"单步预算"的默认分配。没有这玩意儿,
 * 预算只能平均分,结果慢工具把预算吃光、快工具被无辜掐断。</p>
 */
public record ToolSpec(String name, String description, List<String> keywords,
                       boolean write, long baseLatencyMs, ToolHandler handler) {

    public static Builder of(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final List<String> keywords = new java.util.ArrayList<>();
        private boolean write;
        private long baseLatencyMs = 60;
        private ToolHandler handler = (args, ctx) -> ToolResult.of(java.util.Map.of(), "空实现");

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder keywords(String... words) {
            this.keywords.addAll(List.of(words));
            return this;
        }

        public Builder write() {
            this.write = true;
            return this;
        }

        public Builder latency(long ms) {
            this.baseLatencyMs = ms;
            return this;
        }

        public Builder handler(ToolHandler handler) {
            this.handler = handler;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(name, description, List.copyOf(keywords), write, baseLatencyMs, handler);
        }
    }
}
