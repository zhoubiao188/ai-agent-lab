package com.example.memory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型发起的一次工具调用。
 * <p>序列化后的结构必须与 OpenAI 协议一致:{@code {id, type:"function", function:{name, arguments}}},
 * 否则把历史消息原样回放给模型时会被 400 拒掉。{@code arguments} 始终是 JSON <b>字符串</b>,
 * 不是对象 —— 这是流式增量聚合的天然结果,也避免了二次序列化时的转义问题。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(String id, String type, Function function) {

    public record Function(String name, String arguments) {
    }

    public static ToolCall of(String id, String name, String arguments) {
        return new ToolCall(id, "function", new Function(name, arguments == null ? "{}" : arguments));
    }

    public String name() {
        return function == null ? "" : function.name();
    }

    public String arguments() {
        return function == null || function.arguments() == null ? "{}" : function.arguments();
    }
}
