package com.example.multiagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划里的值引用,形如 {@code $s1.paidAmount}。
 *
 * <p>这是"计划"和"调用列表"的分水岭:有引用,步骤之间才有数据流;有数据流,
 * 才谈得上"顺序错没错"。一堆互不引用、各写死参数的调用,顺序怎么排都"能跑",
 * 也正是这种计划最容易出错且最难发现。</p>
 */
public final class Refs {

    private static final Pattern REF = Pattern.compile("\\$([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)");

    private Refs() {
    }

    /** 一处引用:来自哪个步骤的哪个字段 */
    public record Ref(String stepId, String field) {
        @Override
        public String toString() {
            return "$" + stepId + "." + field;
        }
    }

    /** 递归扫出参数里的全部引用(去重,保持出现顺序) */
    public static List<Ref> scan(JsonNode node) {
        List<Ref> refs = new ArrayList<>();
        collect(node, refs);
        return refs;
    }

    private static void collect(JsonNode node, List<Ref> out) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            Matcher m = REF.matcher(node.asText());
            while (m.find()) {
                Ref ref = new Ref(m.group(1), m.group(2));
                if (!out.contains(ref)) {
                    out.add(ref);
                }
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> collect(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }

    /** 把引用整体替换成运行时值;解析不到就原样保留,留给调用层报错 */
    public static ObjectNode resolve(ObjectNode args, java.util.function.Function<Ref, JsonNode> values) {
        return (ObjectNode) resolveNode(args, values);
    }

    private static JsonNode resolveNode(JsonNode node, java.util.function.Function<Ref, JsonNode> values) {
        if (node.isTextual()) {
            Matcher m = REF.matcher(node.asText());
            if (m.matches()) {
                JsonNode v = values.apply(new Ref(m.group(1), m.group(2)));
                return v == null ? node : v;
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> obj.set(e.getKey(), resolveNode(e.getValue(), values)));
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> arr.add(resolveNode(child, values)));
            return arr;
        }
        return node;
    }
}
