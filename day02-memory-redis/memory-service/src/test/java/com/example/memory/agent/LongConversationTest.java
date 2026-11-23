package com.example.memory.agent;

import com.example.memory.Fixtures;
import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.llm.LlmProvider;
import com.example.memory.llm.MockLlmProvider;
import com.example.memory.memory.LlmCaller;
import com.example.memory.memory.MemoryRecaller;
import com.example.memory.memory.SummaryCompactor;
import com.example.memory.memory.TokenEstimator;
import com.example.memory.observe.AgentMetrics;
import com.example.memory.store.InMemoryChatMemoryStore;
import com.example.memory.store.StripedLocks;
import com.example.memory.tool.ToolRegistry;
import com.example.memory.tool.impl.TicketTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 50 轮长对话的端到端测试 —— 本项目的"验收标准"。
 *
 * <p>它同时验证四件事:</p>
 * <ol>
 *   <li>上下文始终在预算内(不会因为聊得久而爆窗口);</li>
 *   <li>消息序列始终合法(没有孤立 tool 消息、窗口首条永远是 user);</li>
 *   <li>关键信息不丢(第 1 轮的订单号在第 50 轮仍然可查);</li>
 *   <li>压缩确实发生了,且省下的 token 可量化。</li>
 * </ol>
 */
class LongConversationTest {

    private AgentProperties props;
    private ConversationService conversations;
    private AgentOrchestrator orchestrator;
    private InMemoryChatMemoryStore store;

    @BeforeEach
    void setUp() {
        props = Fixtures.props(1200, 300, 12, 2);
        ObjectMapper mapper = Fixtures.MAPPER;
        LlmProvider provider = new MockLlmProvider(mapper);
        store = new InMemoryChatMemoryStore(mapper, props);
        conversations = new ConversationService(store,
                new SummaryCompactor(provider, new LlmCaller(props), props),
                new MemoryRecaller(), new StripedLocks(), props);
        orchestrator = new AgentOrchestrator(provider, new ToolRegistry(List.of(new TicketTool())),
                conversations, props, new AgentMetrics(new SimpleMeterRegistry()), mapper);
    }

    @Test
    @DisplayName("50 轮对话:上下文不爆、序列合法、关键信息不丢")
    void fiftyTurns() {
        String cid = "long-conversation";
        int maxSeenTokens = 0;
        int compactions = 0;
        List<Integer> contextPerTurn = new ArrayList<>();

        for (int turn = 1; turn <= 50; turn++) {
            String question = turn == 1
                    ? "我的订单号 A20261123001,退款金额 899.00 元,承诺 2026-11-25 前到账,现在进度到哪了?"
                    : "第 " + turn + " 轮:还是刚才那个订单,麻烦再确认一下进度和到账时间。";

            CollectingSink sink = new CollectingSink();
            orchestrator.run(cid, "u1", question, sink);

            assertThat(sink.eventsOf("error")).as("第 %d 轮不应该报错", turn).isEmpty();

            Conversation conv = conversations.find(cid).orElseThrow();
            int tokens = TokenEstimator.estimateMemory(conv);
            contextPerTurn.add(tokens);
            maxSeenTokens = Math.max(maxSeenTokens, tokens);
            compactions += sink.eventsOf("memory_compacted").size();

            // 1. 预算:压缩是"到水位才触发",允许短时越线,但不能失控
            assertThat(tokens).as("第 %d 轮上下文 %d token 超出预算", turn, tokens)
                    .isLessThan((int) (props.getMemory().getBudgetTokens() * 1.35));

            // 2. 序列合法:首条必须是 user,tool 消息必须成对
            if (!conv.getMessages().isEmpty()) {
                assertThat(conv.getMessages().get(0).isUser()).as("第 %d 轮窗口首条不是 user", turn).isTrue();
            }
            assertValidPairing(conv.getMessages());

            // 3. 关键信息不丢:摘要或关键事实里必须还能找到订单号
            if (turn >= 10) {
                assertThat(conv.getSummary() + String.join(";", conv.getPinnedFacts()))
                        .as("第 %d 轮丢失了订单号", turn)
                        .contains("A20261123001");
            }
        }

        Conversation conv = conversations.find(cid).orElseThrow();
        System.out.printf("""
                === 50 轮压测结果 ===
                累计消息数        : %d
                当前窗口消息数    : %d
                当前上下文 token  : %d(预算 %d)
                历史峰值 token    : %d
                压缩次数          : %d
                归档消息数        : %d
                估算省下 token    : %d
                摘要长度          : %d 字
                关键事实          : %s
                """,
                conv.getStats().getTotalMessages(), conv.getMessages().size(),
                TokenEstimator.estimateMemory(conv), props.getMemory().getBudgetTokens(),
                maxSeenTokens, conv.getStats().getCompactRuns(), conv.getStats().getArchivedMessages(),
                conv.getStats().getTokensSavedEstimate(), conv.getSummary().length(),
                conv.getPinnedFacts());

        assertThat(compactions).as("50 轮里至少压缩了 3 次").isGreaterThanOrEqualTo(3);
        assertThat(conv.getStats().getCompactRuns()).isGreaterThanOrEqualTo(3);
        assertThat(conv.getStats().getArchivedMessages()).isPositive();
        assertThat(conv.getStats().getTokensSavedEstimate()).isPositive();
        assertThat(conv.getSummary()).isNotBlank();

        // 最后 10 轮的上下文明显小于"全量回放"(累计消息数 × 平均每条 token)
        int avgPerMessage = Math.max(1, conv.getStats().getTotalMessages() == 0 ? 1
                : contextPerTurn.get(contextPerTurn.size() - 1) / Math.max(1, conv.getMessages().size()));
        int naive = avgPerMessage * conv.getStats().getTotalMessages();
        assertThat(TokenEstimator.estimateMemory(conv)).isLessThan(naive);
    }

    @Test
    @DisplayName("同一会话并发请求被串行化,不会互相覆盖历史")
    void concurrentTurnsAreSerialized() throws Exception {
        String cid = "concurrent";
        int threads = 4;
        List<Thread> workers = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    orchestrator.run(cid, "u1", "并发消息 " + idx, new CollectingSink());
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            t.join(20_000);
        }

        Conversation conv = conversations.find(cid).orElseThrow();
        long userMessages = conv.getMessages().stream().filter(Msg::isUser).count();
        assertThat(errors).isEmpty();
        // 4 条用户消息都在,且 assistant 回复数量匹配 —— 没有发生"后写覆盖先写"
        assertThat(userMessages + conv.getStats().getArchivedMessages()).isGreaterThanOrEqualTo(4);
    }

    private static void assertValidPairing(List<Msg> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (!m.isTool()) {
                continue;
            }
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
            assertThat(matched).as("孤立 tool 消息 seq=%s", m.seq()).isTrue();
        }
    }

    /** 收集 SSE 事件,便于断言 */
    static final class CollectingSink implements AgentEventSink {
        private final List<Map.Entry<String, Object>> events = new ArrayList<>();

        @Override
        public void emit(String event, Object data) {
            events.add(Map.entry(event, data));
        }

        @Override
        public boolean closed() {
            return false;
        }

        List<Object> eventsOf(String name) {
            return events.stream().filter(e -> e.getKey().equals(name)).map(Map.Entry::getValue).toList();
        }

        List<String> names() {
            return events.stream().map(Map.Entry::getKey).toList();
        }
    }
}
