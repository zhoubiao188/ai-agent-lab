package com.example.memory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 会话中的一条消息。
 *
 * <p>相比 Day 01 的纯内存版本,这里多了两个字段,它们是"可持久化记忆"的地基:</p>
 * <ul>
 *   <li>{@code seq} —— 会话内单调递增的序号。压缩摘要需要记录"我到底覆盖到哪一条",
 *       没有序号就只能靠数组下标,而数组一旦被裁剪,下标全部错位,摘要覆盖率立刻失真;</li>
 *   <li>{@code createdAt} —— 归档召回时做时间衰减,也用于前端展示与审计。</li>
 * </ul>
 *
 * <p>注意 {@code role=tool} 的消息必须带 {@code toolCallId},且必须紧跟在带 {@code toolCalls}
 * 的 assistant 消息之后。这条约束贯穿整个记忆模块:裁剪、压缩、归档召回<b>都不能拆散这对消息</b>,
 * 否则下一次请求必然 400。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Msg(
        long seq,
        String role,
        String content,
        String toolCallId,
        String name,
        List<ToolCall> toolCalls,
        long createdAt
) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static Msg system(long seq, String content) {
        return new Msg(seq, ROLE_SYSTEM, content, null, null, null, System.currentTimeMillis());
    }

    public static Msg user(long seq, String content) {
        return new Msg(seq, ROLE_USER, content, null, null, null, System.currentTimeMillis());
    }

    public static Msg assistant(long seq, String content, List<ToolCall> toolCalls) {
        return new Msg(seq, ROLE_ASSISTANT, content, null, null, toolCalls, System.currentTimeMillis());
    }

    public static Msg tool(long seq, String toolCallId, String name, String content) {
        return new Msg(seq, ROLE_TOOL, content, toolCallId, name, null, System.currentTimeMillis());
    }

    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @JsonIgnore
    public boolean isTool() {
        return ROLE_TOOL.equals(role);
    }

    @JsonIgnore
    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    /** 用于摘要/召回的可读文本:把工具调用也展开成人话,否则摘要里会出现"用户问了,然后什么都没有"。 */
    @JsonIgnore
    public String readable() {
        StringBuilder sb = new StringBuilder();
        if (content != null && !content.isBlank()) {
            sb.append(content.trim());
        }
        if (hasToolCalls()) {
            for (ToolCall c : toolCalls) {
                sb.append(" [调用工具 ").append(c.name()).append(' ').append(c.arguments()).append(']');
            }
        }
        if (isTool()) {
            sb.insert(0, "[工具 " + (name == null ? "?" : name) + " 返回] ");
        }
        return sb.toString();
    }
}
