package com.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 极简 JSON Schema 构造器。只为工具声明服务,不做完整 JSON Schema 校验,
 * 但足以让模型正确理解参数(类型/描述/是否必填)。
 */
public final class Schemas {

    private Schemas() {
    }

    public static ObjectNode object() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        root.set("properties", JsonNodeFactory.instance.objectNode());
        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        root.set("required", required);
        return root;
    }

    public static ObjectNode string(ObjectNode schema, String name, String description, boolean required) {
        return property(schema, name, "string", description, required);
    }

    public static ObjectNode number(ObjectNode schema, String name, String description, boolean required) {
        return property(schema, name, "number", description, required);
    }

    public static ObjectNode bool(ObjectNode schema, String name, String description, boolean required) {
        return property(schema, name, "boolean", description, required);
    }

    private static ObjectNode property(ObjectNode schema, String name, String type, String description, boolean required) {
        ObjectNode prop = JsonNodeFactory.instance.objectNode();
        prop.put("type", type);
        prop.put("description", description);
        ((ObjectNode) schema.get("properties")).set(name, prop);
        if (required) {
            ((ArrayNode) schema.get("required")).add(name);
        }
        return schema;
    }

    /** 从入参里安全读字符串,缺失或类型不对时返回默认值。 */
    public static String str(JsonNode args, String field, String defaultValue) {
        JsonNode n = args == null ? null : args.get(field);
        return n == null || n.isNull() ? defaultValue : n.asText(defaultValue);
    }
}
