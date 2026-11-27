package com.example.multiagent.spec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 校验器(只认 {@link JsonSchema} 支持的那个子集)。
 *
 * <p>这里有一条原则:<b>校验器只校验,绝不偷偷做类型转换。</b>
 * 早期版本我让它在发现 {@code "12"} 时自动转成 12,结果很糟——
 * 参数修复逻辑永远不触发,也就永远不知道模型到底在哪些地方出错;
 * 统计报表里"参数错误 0 次",而线上退款金额全是被截断的整数。</p>
 *
 * <p>所以:校验器报错,修复器负责改,两者分开,谁干的活可度量。</p>
 */
public final class JsonSchemaValidator {

    public List<Violation> validate(JsonNode node, JsonSchema schema) {
        List<Violation> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            out.add(new Violation("/", Violation.Code.TYPE_MISMATCH, "参数整体为空,期望 " + schema.type()));
            return out;
        }
        walk("", node, schema, out);
        return out;
    }

    private void walk(String path, JsonNode node, JsonSchema schema, List<Violation> out) {
        String type = schema.type() == null ? "object" : schema.type();
        switch (type) {
            case "object" -> checkObject(path, node, schema, out);
            case "string" -> checkString(path, node, schema, out);
            case "integer" -> checkInteger(path, node, schema, out);
            case "number" -> checkNumber(path, node, schema, out);
            case "boolean" -> {
                if (!node.isBoolean()) {
                    out.add(typeError(path, "boolean", node));
                }
            }
            default -> {
                // 未声明的类型不做判断,避免" schema 写错"变成"工具不可用"
            }
        }
    }

    private void checkObject(String path, JsonNode node, JsonSchema schema, List<Violation> out) {
        if (!node.isObject()) {
            out.add(typeError(path, "object", node));
            return;
        }
        for (String key : schema.required()) {
            if (!node.hasNonNull(key)) {
                out.add(new Violation(child(path, key), Violation.Code.MISSING_REQUIRED,
                        "缺少必填字段 " + key));
            }
        }
        Set<String> known = new HashSet<>(schema.properties().keySet());
        var it = node.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            if (!known.contains(field)) {
                out.add(new Violation(child(path, field), Violation.Code.UNKNOWN_FIELD,
                        "未声明的字段 " + field + ",将被丢弃"));
            }
        }
        for (Map.Entry<String, JsonSchema> e : schema.properties().entrySet()) {
            JsonNode value = node.get(e.getKey());
            if (value != null && !value.isNull()) {
                walk(child(path, e.getKey()), value, e.getValue(), out);
            }
        }
    }

    private void checkString(String path, JsonNode node, JsonSchema schema, List<Violation> out) {
        if (!node.isTextual()) {
            out.add(typeError(path, "string", node));
            return;
        }
        String v = node.asText();
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()
                && !schema.enumValues().contains(v)) {
            out.add(new Violation(path, Violation.Code.ENUM_MISMATCH,
                    "取值 " + quote(v) + " 不在允许集合 " + schema.enumValues() + " 内"));
        }
        if (schema.pattern() != null && !v.matches(schema.pattern())) {
            out.add(new Violation(path, Violation.Code.PATTERN_MISMATCH,
                    "取值 " + quote(v) + " 不匹配模式 " + schema.pattern()));
        }
    }

    private void checkInteger(String path, JsonNode node, JsonSchema schema, List<Violation> out) {
        if (!node.isIntegralNumber()) {
            out.add(typeError(path, "integer", node));
            return;
        }
        checkRange(path, node.asDouble(), schema, out);
    }

    private void checkNumber(String path, JsonNode node, JsonSchema schema, List<Violation> out) {
        if (!node.isNumber()) {
            out.add(typeError(path, "number", node));
            return;
        }
        checkRange(path, node.asDouble(), schema, out);
    }

    private void checkRange(String path, double v, JsonSchema schema, List<Violation> out) {
        if (schema.minimum() != null && v < schema.minimum()) {
            out.add(new Violation(path, Violation.Code.OUT_OF_RANGE,
                    "取值 " + v + " 小于下限 " + schema.minimum()));
        }
        if (schema.maximum() != null && v > schema.maximum()) {
            out.add(new Violation(path, Violation.Code.OUT_OF_RANGE,
                    "取值 " + v + " 大于上限 " + schema.maximum()));
        }
    }

    private Violation typeError(String path, String expected, JsonNode actual) {
        return new Violation(path, Violation.Code.TYPE_MISMATCH,
                "期望 " + expected + ",实际是 " + kind(actual) + " " + shortValue(actual));
    }

    private String kind(JsonNode node) {
        if (node == null) {
            return "null";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isObject()) {
            return "object";
        }
        return node.getNodeType().name().toLowerCase();
    }

    private String shortValue(JsonNode node) {
        String s = String.valueOf(node);
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private String quote(String v) {
        return "\"" + v + "\"";
    }

    private static String child(String path, String key) {
        return path + "/" + key;
    }
}
