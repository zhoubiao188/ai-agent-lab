package com.example.tool.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个极小的 JSON Schema 子集。够用、能读懂、可单测,不引第三方校验库。
 *
 * <p>刻意只支持 type / required / enum / minimum / maximum / pattern 六件事。
 * 真实项目里工具参数 90% 的错误都落在这六类上,再加 allOf/oneOf 只会让
 * "模型到底错在哪"更难定位。</p>
 */
public record JsonSchema(
        String type,
        String description,
        Map<String, JsonSchema> properties,
        List<String> required,
        List<String> enumValues,
        Double minimum,
        Double maximum,
        String pattern
) {

    public static JsonSchema object(Map<String, JsonSchema> props, String... required) {
        return new JsonSchema("object", null, new LinkedHashMap<>(props), List.of(required),
                null, null, null, null);
    }

    public static JsonSchema string(String desc) {
        return new JsonSchema("string", desc, Map.of(), List.of(), null, null, null, null);
    }

    public static JsonSchema enumOf(String desc, String... values) {
        return new JsonSchema("string", desc, Map.of(), List.of(), List.of(values), null, null, null);
    }

    public static JsonSchema integer(String desc, double min, double max) {
        return new JsonSchema("integer", desc, Map.of(), List.of(), null, min, max, null);
    }

    public static JsonSchema number(String desc, double min, double max) {
        return new JsonSchema("number", desc, Map.of(), List.of(), null, min, max, null);
    }

    public static JsonSchema bool(String desc) {
        return new JsonSchema("boolean", desc, Map.of(), List.of(), null, null, null, null);
    }

    public JsonSchema withPattern(String regex) {
        return new JsonSchema(type, description, properties, required, enumValues, minimum, maximum, regex);
    }

    public JsonSchema described(String desc) {
        return new JsonSchema(type, desc, properties, required, enumValues, minimum, maximum, pattern);
    }
}
