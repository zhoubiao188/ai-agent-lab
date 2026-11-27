package com.example.multiagent.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 计划里的一个步骤。
 *
 * <p>除了"调哪个工具、传什么参数",它额外声明了四件事,这四件事才是可核对的部分:</p>
 * <ul>
 *   <li>{@code covers} —— 这一步覆盖了用户的哪个诉求(意图槽位)。用于查"拆漏"。</li>
 *   <li>{@code produces} —— 这一步承诺产出哪些字段。用于查"计划说谎"。</li>
 *   <li>{@code consumes} —— 这一步需要别人产出的哪些字段。用于查"数据流断没断"。</li>
 *   <li>{@code dependsOn} —— 显式依赖。真实模型经常只写一半,所以还会扫参数里的 {@code $ref}。</li>
 * </ul>
 *
 * <p>没有这四行声明,计划就只是一串调用;有了它们,计划才可以被机器验收。</p>
 */
public record Step(
        String id,
        String title,
        String tool,
        ObjectNode args,
        List<String> dependsOn,
        List<String> covers,
        List<String> produces,
        List<String> consumes
) {

    public Step {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        covers = covers == null ? List.of() : List.copyOf(covers);
        produces = produces == null ? List.of() : List.copyOf(produces);
        consumes = consumes == null ? List.of() : List.copyOf(consumes);
    }

    public static Step of(String id, String tool, ObjectNode args) {
        return new Step(id, tool, tool, args, List.of(), List.of(), List.of(), List.of());
    }

    public Step titled(String newTitle) {
        return new Step(id, newTitle, tool, args, dependsOn, covers, produces, consumes);
    }

    public Step depends(String... deps) {
        return new Step(id, title, tool, args, List.of(deps), covers, produces, consumes);
    }

    public Step withDependsOn(List<String> deps) {
        return new Step(id, title, tool, args, deps, covers, produces, consumes);
    }

    public Step covering(String... slots) {
        return new Step(id, title, tool, args, dependsOn, List.of(slots), produces, consumes);
    }

    public Step producing(String... fields) {
        return new Step(id, title, tool, args, dependsOn, covers, List.of(fields), consumes);
    }

    public Step consuming(String... fields) {
        return new Step(id, title, tool, args, dependsOn, covers, produces, List.of(fields));
    }

    public Step withTool(String newTool) {
        return new Step(id, title, newTool, args, dependsOn, covers, produces, consumes);
    }

    public Step withArgs(ObjectNode newArgs) {
        return new Step(id, title, tool, newArgs, dependsOn, covers, produces, consumes);
    }

    /** 参数里的全部引用(含未声明为依赖的) */
    public List<Refs.Ref> refs() {
        return Refs.scan(args);
    }

    @Override
    public String toString() {
        return id + "(" + tool + " ← " + dependsOn + ")";
    }
}
