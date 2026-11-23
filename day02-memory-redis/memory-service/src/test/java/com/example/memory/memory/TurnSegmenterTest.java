package com.example.memory.memory;

import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.domain.ToolCall;
import com.example.memory.memory.TurnSegmenter.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnSegmenterTest {

    @Test
    @DisplayName("以 user 消息为切点,一轮内的 assistant/tool 不会被拆开")
    void segmentByUserBoundary() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("我要退款");
        c.appendAssistant("我查一下", List.of(ToolCall.of("call_1", "create_support_ticket", "{}")));
        c.appendTool("call_1", "create_support_ticket", "工单号 T20261123001");
        c.appendAssistant("已为你创建工单", null);
        c.appendUser("多久到账");
        c.appendAssistant("3 个工作日", null);

        List<Segment> segments = TurnSegmenter.segment(c.getMessages());

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).messages()).hasSize(4);
        assertThat(segments.get(0).startSeq()).isEqualTo(1);
        assertThat(segments.get(0).endSeq()).isEqualTo(4);
        assertThat(segments.get(1).messages()).hasSize(2);
        // 第一轮必须完整包含 tool_calls 与对应 tool 结果
        assertThat(segments.get(0).messages().get(1).hasToolCalls()).isTrue();
        assertThat(segments.get(0).messages().get(2).toolCallId()).isEqualTo("call_1");
    }

    @Test
    @DisplayName("以非 user 开头的孤儿消息自成一轮,便于优先清理")
    void orphanHead() {
        List<Msg> msgs = List.of(
                Msg.assistant(1, "上一条被截断的回答", null),
                Msg.tool(2, "call_x", "calculator", "ERROR: 超时"),
                Msg.user(3, "重新问一次"),
                Msg.assistant(4, "好的", null));

        List<Segment> segments = TurnSegmenter.segment(msgs);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).containsUser()).isFalse();
        assertThat(segments.get(0).messages()).hasSize(2);
        assertThat(segments.get(1).containsUser()).isTrue();
    }

    @Test
    @DisplayName("空输入返回空列表,不抛异常")
    void emptyInput() {
        assertThat(TurnSegmenter.segment(List.of())).isEmpty();
        assertThat(TurnSegmenter.segment(null)).isEmpty();
    }

    @Test
    @DisplayName("digest 会截断超长工具返回,并保留角色前缀")
    void digestTruncatesLongToolOutput() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("查一下订单");
        c.appendTool("call_1", "get_order", "X".repeat(2000));

        Segment s = TurnSegmenter.segment(c.getMessages()).get(0);
        String digest = s.digest(100);

        assertThat(digest).contains("用户: 查一下订单");
        assertThat(digest).contains("工具: [工具 get_order 返回]");
        assertThat(digest).contains("已截断");
        assertThat(digest.length()).isLessThan(400);
    }
}
