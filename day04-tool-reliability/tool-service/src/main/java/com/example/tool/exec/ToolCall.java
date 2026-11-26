package com.example.tool.exec;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 模型发起的一次工具调用。
 *
 * <p>{@code dependsOn} 是显式依赖。真实模型不会吐这个字段,所以编排器还会额外扫参数里的
 * {@code $callId.field} 引用自动补依赖 —— 两种方式都支持:显式写在插件的元数据里,
 * 隐式从引用推断,后者是兜底。</p>
 */
public record ToolCall(String id, String tool, ObjectNode args, List<String> dependsOn) {

    public ToolCall {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public static ToolCall of(String id, String tool, ObjectNode args) {
        return new ToolCall(id, tool, args, List.of());
    }

    public ToolCall withArgs(ObjectNode newArgs) {
        return new ToolCall(id, tool, newArgs, dependsOn);
    }

    public ToolCall withDependsOn(List<String> deps) {
        return new ToolCall(id, tool, args, deps);
    }

    /** 参数里是否引用了别的调用结果,例如 {@code $c1.paidAmount} */
    public boolean references(String otherCallId) {
        return args.toString().contains("$" + otherCallId + ".");
    }
}
