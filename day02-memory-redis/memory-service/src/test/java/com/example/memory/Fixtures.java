package com.example.memory;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Msg;
import com.example.memory.domain.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/** 测试用构造器:把 AgentProperties 调到"几轮就压缩"的小水位,便于快速验证。 */
public final class Fixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static AgentProperties props() {
        return props(600, 150, 10, 2);
    }

    public static AgentProperties props(int budgetTokens, int keepTailTokens, int maxMessages, int keepMinSegments) {
        AgentProperties p = new AgentProperties();
        AgentProperties.Memory m = p.getMemory();
        m.setBudgetTokens(budgetTokens);
        m.setKeepTailTokens(keepTailTokens);
        m.setMaxMessages(maxMessages);
        m.setKeepMinSegments(keepMinSegments);
        m.setSummaryMaxChars(400);
        m.setSummaryTimeout(Duration.ofSeconds(5));
        m.setBreakerFailures(2);
        m.setBreakerCooldown(Duration.ofMinutes(5));
        m.setArchiveMaxMessages(200);
        m.setRecallTopK(3);
        m.setRecallMinScore(0.1);
        m.setTtl(Duration.ofDays(1));
        return p;
    }

    /** 造一段"用户问 + 助手带工具调用 + 工具结果"的完整轮次 */
    public static void appendToolTurn(com.example.memory.domain.Conversation c, String question, String toolResult) {
        c.appendUser(question);
        ToolCall call = ToolCall.of("call_1", "create_support_ticket", "{\"summary\":\"退款\"}");
        c.appendAssistant("我先查一下。", List.of(call));
        c.appendTool("call_1", "create_support_ticket", toolResult);
        c.appendAssistant("已处理。", null);
    }

    public static Msg user(long seq, String text) {
        return Msg.user(seq, text);
    }
}
