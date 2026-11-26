package com.example.tool.support;

import com.example.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 测试用的固定配置。所有阈值都调小,让测试跑得快,但比例与线上一致。
 */
public final class TestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj(Object... kv) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof Integer i2) {
                node.put(k, i2);
            } else if (v instanceof Long l) {
                node.put(k, l);
            } else if (v instanceof Double d) {
                node.put(k, d);
            } else if (v instanceof Boolean b) {
                node.put(k, b);
            } else {
                node.put(k, String.valueOf(v));
            }
        }
        return node;
    }

    /** 默认:重试 3 次、退避 10~80ms、无故障注入 */
    public static ToolProperties props() {
        return props(0.0);
    }

    /** 只开参数污染,不开链路故障:用来单独测"修复"这一件事 */
    public static ToolProperties corrupting(double corruptRate) {
        return new ToolProperties(
                300, 3, 10, 80, 0.5,
                true, true, true, true,
                new ToolProperties.Breaker(3000, 10, 0.5, 300, 1),
                new ToolProperties.Parallel(8, true),
                new ToolProperties.Agent(10_000, 1),
                new ToolProperties.Fault(true, 42L, 0, 0, 0, 0, corruptRate),
                new ToolProperties.Bench(20, 42L),
                1.0, false);
    }

    public static ToolProperties props(double faultRate) {
        return new ToolProperties(
                300, 3, 10, 80, 0.5,
                true, true, true, true,
                new ToolProperties.Breaker(3000, 10, 0.5, 300, 1),
                new ToolProperties.Parallel(8, true),
                new ToolProperties.Agent(10_000, 1),
                new ToolProperties.Fault(faultRate > 0, 42L,
                        faultRate, faultRate, faultRate, 0, 0),
                new ToolProperties.Bench(20, 42L),
                1.0, false);
    }
}
