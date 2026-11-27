package com.example.multiagent.spec;

/**
 * 工具声明。声明里最重要的三个字段不是 name 和 description,而是
 * {@code writeOperation} / {@code idempotent} / {@code timeoutMs} ——
 * 它们直接决定重试策略敢不敢动这个工具。
 *
 * <p>写操作 + 非幂等 = 永不自动重试。这条规则救过一次线上:退款接口超时后重试,
 * 用户收到两笔退款,追回来花了两周。</p>
 */
public record ToolSpec(
        String name,
        String description,
        JsonSchema schema,
        boolean writeOperation,
        /** 幂等:同一 idempotencyKey 重复执行不会产生副作用 */
        boolean idempotent,
        long timeoutMs
) {

    public static ToolSpec read(String name, String desc, JsonSchema schema) {
        return new ToolSpec(name, desc, schema, false, true, 0);
    }

    public static ToolSpec write(String name, String desc, JsonSchema schema, boolean idempotent) {
        return new ToolSpec(name, desc, schema, true, idempotent, 0);
    }
}
