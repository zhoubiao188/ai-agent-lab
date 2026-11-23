package com.example.memory.memory;

import com.example.memory.Fixtures;
import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.domain.ToolCall;
import com.example.memory.llm.LlmProvider;
import com.example.memory.llm.MockLlmProvider;
import com.example.memory.llm.dto.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCompactorTest {

    private final AgentProperties props = Fixtures.props(400, 60, 6, 2);

    private SummaryCompactor compactor(LlmProvider provider) {
        return new SummaryCompactor(provider, new LlmCaller(props), props);
    }

    private Conversation longConversation() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("订单号 A20261123001 的退款 899.00 元什么时候到账?");
        c.appendAssistant("我帮你查一下订单 A20261123001 的退款进度。", null);
        c.appendUser("好的,顺便问下退款一般几个工作日?");
        c.appendAssistant("原路退回通常 1-3 个工作日,银行卡可能延后到 5 个工作日。", null);
        c.appendUser("那如果超过 5 个工作日还没到呢?");
        c.appendAssistant("超过 5 个工作日可以申请人工核查,我们会调取支付流水。", null);
        c.appendUser("行,帮我记一下要在 2026-11-25 前确认到账。");
        c.appendAssistant("已记录:2026-11-25 前确认订单 A20261123001 的退款到账。", null);
        c.appendUser("现在进度到哪了?");
        c.appendAssistant("当前处于财务复核中。", null);
        return c;
    }

    @Test
    @DisplayName("未到水位线时不压缩")
    void skipBelowWatermark() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("你好");
        c.appendAssistant("你好,有什么可以帮你?", null);

        SummaryCompactor.Result r = compactor(new MockLlmProvider(Fixtures.MAPPER)).maybeCompact(c, false);

        assertThat(r.compacted()).isFalse();
        assertThat(c.getMessages()).hasSize(2);
        assertThat(c.getSummary()).isEmpty();
    }

    @Test
    @DisplayName("超过水位线时折叠最早的整轮,保留最近的上下文")
    void compactOverWatermark() {
        Conversation c = longConversation();
        int beforeTokens = TokenEstimator.estimateMemory(c);
        int beforeMessages = c.getMessages().size();

        SummaryCompactor.Result r = compactor(new MockLlmProvider(Fixtures.MAPPER)).maybeCompact(c, false);

        assertThat(r.compacted()).isTrue();
        assertThat(r.degraded()).isFalse();
        assertThat(r.mode()).isEqualTo("llm");
        assertThat(r.beforeTokens()).isEqualTo(beforeTokens);
        assertThat(r.afterTokens()).isLessThan(beforeTokens);
        assertThat(r.foldedMessages()).isGreaterThan(0);
        assertThat(c.getMessages().size()).isLessThan(beforeMessages);
        assertThat(c.getSummary()).isNotBlank();
        assertThat(c.getSummaryCoveredSeq()).isEqualTo(r.coveredSeq());
        assertThat(c.getStats().getCompactRuns()).isEqualTo(1);

        // 保留的窗口必须以 user 开头,且轮数不少于 keepMinSegments
        assertThat(c.getMessages().get(0).isUser()).isTrue();
        assertThat(TurnSegmenter.segment(c.getMessages())).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("压缩后订单号等关键事实必须还在(pinned facts 兜底)")
    void keepsPinnedFacts() {
        Conversation c = longConversation();
        compactor(new MockLlmProvider(Fixtures.MAPPER)).maybeCompact(c, false);

        assertThat(c.getPinnedFacts()).anyMatch(f -> f.contains("A20261123001"));
        assertThat(c.getPinnedFacts()).anyMatch(f -> f.contains("899.00"));
        assertThat(c.getSummary()).contains("A20261123001");
    }

    @Test
    @DisplayName("压缩不能拆散 assistant(tool_calls) 与 tool 结果")
    void neverSplitsToolPairs() {
        Conversation c = Conversation.create("c1", "u1");
        for (int i = 0; i < 5; i++) {
            c.appendUser("第 " + i + " 次咨询退款进度,订单号 A2026112300" + i);
            ToolCall call = ToolCall.of("call_" + i, "get_order_status", "{\"id\":\"A2026112300" + i + "\"}");
            c.appendAssistant("查一下", List.of(call));
            c.appendTool("call_" + i, "get_order_status", "状态:财务复核中,预计 3 个工作日");
            c.appendAssistant("目前是财务复核中。", null);
        }

        compactor(new MockLlmProvider(Fixtures.MAPPER)).maybeCompact(c, false);

        assertValidToolPairing(c.getMessages());
        assertThat(c.getMessages().get(0).isUser()).isTrue();
    }

    @Test
    @DisplayName("摘要模型失败时降级为本地摘要,不阻塞对话")
    void degradeWhenSummarizerFails() {
        Conversation c = longConversation();
        SummaryCompactor.Result r = compactor(new FailingProvider()).maybeCompact(c, false);

        assertThat(r.compacted()).isTrue();
        assertThat(r.degraded()).isTrue();
        assertThat(r.mode()).isEqualTo("local-fallback");
        assertThat(c.getSummary()).contains("摘要模型不可用");
        // 本地摘要仍然靠 pinned facts 保住了单号
        assertThat(c.getSummary()).contains("A20261123001");
        assertThat(c.getStats().getSummarizerFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("连续失败达到阈值后熔断,后续直接走本地摘要")
    void breakerOpensAfterRepeatedFailures() {
        SummaryCompactor sc = compactor(new FailingProvider());

        sc.maybeCompact(longConversation(), false);
        sc.maybeCompact(longConversation(), false);
        assertThat(sc.breakerOpen()).isTrue();

        Conversation c = longConversation();
        SummaryCompactor.Result r = sc.maybeCompact(c, false);
        assertThat(r.mode()).isEqualTo("local-breaker");
        assertThat(r.degraded()).isTrue();
    }

    @Test
    @DisplayName("压缩收益为负时回滚,不做无用功")
    void skipWhenNotProfitable() {
        // 预算很小 + 保留窗口很大:折叠掉的内容还没摘要本身长,压缩只会让上下文更大
        AgentProperties tiny = Fixtures.props(300, 600, 4, 3);
        Conversation c = longConversation();
        int before = TokenEstimator.estimateMemory(c);

        SummaryCompactor.Result r = new SummaryCompactor(new MockLlmProvider(Fixtures.MAPPER),
                new LlmCaller(tiny), tiny).maybeCompact(c, false);

        assertThat(r.compacted()).isFalse();
        assertThat(r.mode()).isEqualTo("skipped:not_profitable");
        assertThat(TokenEstimator.estimateMemory(c)).isEqualTo(before);
        assertThat(c.getSummary()).isEmpty();
    }

    @Test
    @DisplayName("轮次太少时即使 force 也不压缩")
    void skipWhenTooFewSegments() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("你好");
        c.appendAssistant("你好", null);
        c.appendUser("在吗");
        c.appendAssistant("在的", null);

        assertThat(compactor(new MockLlmProvider(Fixtures.MAPPER)).maybeCompact(c, true).compacted()).isFalse();
    }

    @Test
    @DisplayName("sanitize 去掉代码围栏与'摘要:'前缀,并按句号截断")
    void sanitizeOutput() {
        String fenced = "```\n【用户目标】退款\n```";
        assertThat(SummaryCompactor.sanitize(fenced, 700)).isEqualTo("【用户目标】退款");

        String prefixed = "摘要:【用户目标】退款";
        assertThat(SummaryCompactor.sanitize(prefixed, 700)).isEqualTo("【用户目标】退款");

        String longText = "第一句话。" + "第二句话。".repeat(50);
        String clipped = SummaryCompactor.sanitize(longText, 30);
        assertThat(clipped.length()).isLessThanOrEqualTo(31);
        assertThat(clipped).endsWith("。…");
    }

    private static void assertValidToolPairing(List<Msg> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (m.isTool()) {
                // 往前找最近的 assistant,它必须包含这条 tool_call_id
                boolean matched = false;
                for (int j = i - 1; j >= 0 && !messages.get(j).isUser(); j--) {
                    Msg prev = messages.get(j);
                    if (prev.hasToolCalls()) {
                        Set<String> ids = new HashSet<>();
                        prev.toolCalls().forEach(tc -> ids.add(tc.id()));
                        matched = ids.contains(m.toolCallId());
                        break;
                    }
                }
                assertThat(matched).as("tool 消息 seq=%s 找不到对应的 assistant(tool_calls)", m.seq()).isTrue();
            }
        }
    }

    /** 永远失败的 provider,用来验证降级与熔断 */
    private static final class FailingProvider implements LlmProvider {
        @Override
        public String name() {
            return "failing";
        }

        @Override
        public void stream(LlmRequest request, StreamListener listener) {
            listener.onError(new IllegalStateException("429 Too Many Requests"));
        }
    }
}
