package com.example.memory.agent;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.domain.ToolCall;
import com.example.memory.llm.LlmProvider;
import com.example.memory.llm.dto.LlmRequest;
import com.example.memory.memory.MemoryRecaller;
import com.example.memory.memory.SummaryCompactor;
import com.example.memory.memory.TokenEstimator;
import com.example.memory.observe.AgentMetrics;
import com.example.memory.tool.AgentTool;
import com.example.memory.tool.ToolContext;
import com.example.memory.tool.ToolRegistry;
import com.example.memory.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 编排器。相比 Day 01,这里多了三件事,其余仍是那个 while 循环:
 *
 * <pre>
 * beginTurn(加锁 + 追加用户消息 + 压缩 + 召回)
 *   └─> [system + 摘要 + 关键事实 + 召回片段 + 活跃窗口] ──> LLM
 *         ├─ 只回文本 → 落盘 → 结束
 *         └─ 要调工具 → 执行 → 落盘 → 下一步
 * </pre>
 *
 * <p>三个新增点:</p>
 * <ol>
 *   <li><b>上下文分层</b>:system 里按"规则 → 工具 → 长期记忆 → 关键事实 → 相关片段"排列。
 *       顺序不是随意的 —— 模型对上下文末尾更敏感,所以"最近的消息"永远在最后,摘要靠前;</li>
 *   <li><b>每一步落盘</b>:SSE 断连、进程重启都不会丢整轮对话;</li>
 *   <li><b>记忆事件外发</b>:前端能实时看到"刚刚压缩了 12 条消息,省了 3800 token",
 *       这是把黑盒变成可解释的关键一步。</li>
 * </ol>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final LlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final ConversationService conversations;
    private final AgentProperties props;
    private final AgentMetrics metrics;
    private final ObjectMapper mapper;

    private final ExecutorService toolExecutor;

    public AgentOrchestrator(LlmProvider provider,
                             ToolRegistry toolRegistry,
                             ConversationService conversations,
                             AgentProperties props,
                             AgentMetrics metrics,
                             ObjectMapper mapper) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.conversations = conversations;
        this.props = props;
        this.metrics = metrics;
        this.mapper = mapper;
        AtomicInteger seq = new AtomicInteger();
        this.toolExecutor = new ThreadPoolExecutor(
                4, 16, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "agent-tool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        toolExecutor.shutdownNow();
    }

    public record ProviderInfo(String provider, boolean realModel, String model, int maxSteps,
                               String store, List<String> tools) {
    }

    public ProviderInfo info() {
        return new ProviderInfo(provider.name(), provider.realModel(), props.getLlm().getModel(),
                props.getMaxSteps(), conversations.store().type(),
                toolRegistry.specs().stream().map(s -> s.name()).toList());
    }

    public void run(String conversationId, String userId, String userText, AgentEventSink sink) {
        metrics.streamOpened();
        long startedAt = System.nanoTime();
        int steps = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        String result = "ok";

        ConversationService.TurnSession session = null;
        try {
            session = conversations.beginTurn(conversationId, userId, userText);
            Conversation conv = session.conversation();

            sink.emit("message_start", Map.of(
                    "conversationId", conv.getId(),
                    "provider", provider.name(),
                    "model", props.getLlm().getModel(),
                    "realModel", provider.realModel(),
                    "memory", memorySnapshot(conv)));

            SummaryCompactor.Result compaction = session.compaction();
            if (compaction.compacted()) {
                sink.emit("memory_compacted", Map.of(
                        "mode", compaction.mode(),
                        "degraded", compaction.degraded(),
                        "beforeTokens", compaction.beforeTokens(),
                        "afterTokens", compaction.afterTokens(),
                        "foldedMessages", compaction.foldedMessages(),
                        "coveredSeq", compaction.coveredSeq(),
                        "summaryPreview", preview(compaction.summary(), 160)));
            }
            if (!session.recallHits().isEmpty()) {
                sink.emit("memory_recall", Map.of(
                        "hits", session.recallHits().stream()
                                .map(h -> Map.of("startSeq", h.startSeq(), "endSeq", h.endSeq(),
                                        "score", h.score(), "text", preview(h.text(), 200)))
                                .toList()));
            }

            for (steps = 1; steps <= props.getMaxSteps(); steps++) {
                if (sink.closed()) {
                    result = "client_closed";
                    return;
                }

                StepOutcome outcome = runOneStep(conv, session, sink);
                promptTokens += outcome.usage.promptTokens();
                completionTokens += outcome.usage.completionTokens();

                if (!outcome.hasToolCalls()) {
                    String answer = outcome.content.toString();
                    conv.appendAssistant(answer, null);
                    conversations.persist(session);
                    sink.emit("message_end", Map.of(
                            "content", answer,
                            "steps", steps,
                            "finishReason", outcome.finishReason == null ? "stop" : outcome.finishReason,
                            "memory", memorySnapshot(conv)));
                    break;
                }

                if (!outcome.content.isEmpty()) {
                    // 模型先说了句"我查一下"再调工具,这段过渡语已经流给前端了,必须撤回
                    sink.emit("delta_reset", Map.of("reason", "tool_call_started"));
                }
                conv.appendAssistant(outcome.content.isEmpty() ? null : outcome.content.toString(),
                        outcome.toolCalls);
                conversations.persist(session);

                for (ToolCall call : outcome.toolCalls) {
                    if (sink.closed()) {
                        result = "client_closed";
                        return;
                    }
                    ToolResult tr = executeTool(call, conv.getId(), userId);
                    sink.emit("tool_result", Map.of(
                            "id", call.id(),
                            "name", call.name(),
                            "arguments", call.arguments(),
                            "success", tr.success(),
                            "content", tr.content(),
                            "elapsedMs", tr.elapsedMs()));
                    conv.appendTool(call.id(), call.name(), tr.content());
                }
                conversations.persist(session);

                if (steps == props.getMaxSteps()) {
                    String fallback = "抱歉,这个问题需要更多步骤才能处理完,我已经停止继续调用工具。"
                            + "你可以把问题拆小一些再问我,或者回复「转人工」由客服跟进。";
                    conv.appendAssistant(fallback, null);
                    conversations.persist(session);
                    sink.emit("message_end", Map.of("content", fallback, "steps", steps,
                            "finishReason", "max_steps", "memory", memorySnapshot(conv)));
                    result = "max_steps";
                }
            }

            sink.emit("done", Map.of(
                    "steps", Math.min(steps, props.getMaxSteps()),
                    "promptTokens", promptTokens,
                    "completionTokens", completionTokens,
                    "elapsedMs", (System.nanoTime() - startedAt) / 1_000_000));
        } catch (ConversationService.BusyException e) {
            result = "busy";
            sink.emit("error", Map.of("message", e.getMessage(), "type", "BUSY", "retryable", true));
        } catch (ConversationService.ConcurrentUpdateException e) {
            result = "conflict";
            sink.emit("error", Map.of("message", e.getMessage(), "type", "CONFLICT", "retryable", true));
        } catch (Exception e) {
            result = "error";
            log.error("Agent 执行失败 conversationId={}", conversationId, e);
            sink.emit("error", Map.of(
                    "message", Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()),
                    "type", e.getClass().getSimpleName()));
        } finally {
            if (session != null) {
                session.close();
            }
            metrics.requestFinished(result);
            metrics.streamClosed();
        }
    }

    // ---------------------------------------------------------------- 单步

    private static final class StepOutcome {
        final StringBuilder content = new StringBuilder();
        final Map<Integer, ToolCallAcc> accs = new LinkedHashMap<>();
        List<ToolCall> toolCalls = List.of();
        String finishReason;
        LlmProvider.TokenUsage usage = LlmProvider.TokenUsage.unknown();

        boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    private static final class ToolCallAcc {
        final int index;
        String id;
        String name;
        final StringBuilder args = new StringBuilder();

        ToolCallAcc(int index) {
            this.index = index;
        }
    }

    private StepOutcome runOneStep(Conversation conv, ConversationService.TurnSession session,
                                   AgentEventSink sink) {
        StepOutcome outcome = new StepOutcome();
        LlmRequest request = new LlmRequest(props.getLlm().getModel(),
                buildMessages(conv, session), toolRegistry.specs(), props.getLlm().getTemperature());

        long t0 = System.nanoTime();
        provider.stream(request, new LlmProvider.StreamListener() {
            @Override
            public void onContentDelta(String delta) {
                if (delta == null || delta.isEmpty() || sink.closed()) {
                    return;
                }
                outcome.content.append(delta);
                sink.emit("delta", Map.of("text", delta));
            }

            @Override
            public void onToolCallDelta(int index, String id, String name, String argsFragment) {
                ToolCallAcc acc = outcome.accs.computeIfAbsent(index, ToolCallAcc::new);
                if (id != null && !id.isEmpty()) {
                    acc.id = id;
                }
                if (name != null && !name.isEmpty()) {
                    if (acc.name == null) {
                        acc.name = name;
                        sink.emit("tool_start", Map.of("index", index,
                                "id", acc.id == null ? "" : acc.id, "name", name));
                    }
                }
                if (argsFragment != null && !argsFragment.isEmpty()) {
                    acc.args.append(argsFragment);
                }
            }

            @Override
            public void onFinish(String finishReason, LlmProvider.TokenUsage usage) {
                outcome.finishReason = finishReason;
                if (usage != null) {
                    outcome.usage = usage;
                }
            }

            @Override
            public void onError(Throwable error) {
                throw new IllegalStateException("LLM 调用失败: " + error.getMessage(), error);
            }
        });
        metrics.recordLlmLatency(System.nanoTime() - t0);

        outcome.toolCalls = outcome.accs.values().stream()
                .sorted(Comparator.comparingInt(a -> a.index))
                .filter(a -> a.name != null)
                .map(a -> ToolCall.of(a.id == null ? "call_" + a.index : a.id, a.name,
                        a.args.length() == 0 ? "{}" : a.args.toString()))
                .toList();
        return outcome;
    }

    /**
     * 组装上下文。分层顺序见类注释,这里额外说明两个细节:
     * <ul>
     *   <li>摘要块里显式写"冲突时以最近消息为准" —— 不给这句,模型经常把过时摘要当圣旨;</li>
     *   <li>召回片段放在摘要之后、活跃窗口之前,并标注"可能不完整",降低模型对片段的过度信任。</li>
     * </ul>
     */
    private List<Msg> buildMessages(Conversation conv, ConversationService.TurnSession session) {
        List<Msg> messages = new ArrayList<>(conv.getMessages().size() + 3);
        messages.add(Msg.system(0, systemPrompt(conv, session)));
        messages.addAll(conv.getMessages());
        return messages;
    }

    private String systemPrompt(Conversation conv, ConversationService.TurnSession session) {
        StringBuilder sb = new StringBuilder(props.getPrompt().getSystem());

        if (props.getPrompt().isInjectToolCatalog() && toolRegistry.size() > 0) {
            sb.append("\n可用工具清单:\n");
            toolRegistry.specs().forEach(s -> sb.append("- ").append(s.name())
                    .append(": ").append(s.description()).append('\n'));
        }

        if (conv.hasSummary()) {
            sb.append("\n【长期记忆·会话摘要】\n")
                    .append(conv.getSummary().trim()).append('\n')
                    .append("(以上是更早轮次的压缩摘要;与最近消息冲突时,以最近消息为准)\n");
        }

        if (!conv.getPinnedFacts().isEmpty()) {
            sb.append("\n【必须记住的关键信息(原文,不得改写)】\n");
            for (String fact : conv.getPinnedFacts()) {
                sb.append("- ").append(fact).append('\n');
            }
        }

        if (session != null && !session.recallHits().isEmpty()) {
            sb.append('\n').append(MemoryRecaller.render(session.recallHits()))
                    .append("(这些片段可能不完整,引用前先确认与当前问题一致)\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具执行

    private ToolResult executeTool(ToolCall call, String conversationId, String userId) {
        long start = System.nanoTime();
        Optional<AgentTool> maybe = toolRegistry.find(call.name());
        ToolResult result;
        if (maybe.isEmpty()) {
            result = ToolResult.fail("不存在的工具「%s」,可用工具:%s"
                    .formatted(call.name(), toolRegistry.specs().stream().map(s -> s.name()).toList()), 0);
        } else {
            AgentTool tool = maybe.get();
            Future<ToolResult> future = toolExecutor.submit(() -> {
                JsonNode args = readArgs(call.arguments());
                return tool.execute(args, new ToolContext(conversationId, call.id(), userId));
            });
            try {
                result = future.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                result = ToolResult.fail("工具「%s」执行超时(15s),已中断".formatted(call.name()),
                        (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("工具执行异常 name={} args={}", call.name(), call.arguments(), cause);
                result = ToolResult.fail("工具「%s」执行异常: %s".formatted(call.name(), cause.getMessage()),
                        (System.nanoTime() - start) / 1_000_000);
            }
        }
        long elapsed = System.nanoTime() - start;
        metrics.recordToolCall(call.name(), result.success(), elapsed);
        return result;
    }

    private JsonNode readArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            log.warn("工具参数不是合法 JSON: {}", raw);
            return mapper.createObjectNode();
        }
    }

    // ---------------------------------------------------------------- 记忆快照

    /** 前端"记忆水位"面板的数据源,每次 message_start / message_end 都会带一份 */
    private Map<String, Object> memorySnapshot(Conversation conv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tokensUsed", TokenEstimator.estimateMemory(conv));
        m.put("budgetTokens", props.getMemory().getBudgetTokens());
        m.put("summaryTokens", TokenEstimator.estimate(conv.getSummary()));
        m.put("historyMessages", conv.getMessages().size());
        m.put("summaryCoveredSeq", conv.getSummaryCoveredSeq());
        m.put("compactRuns", conv.getStats().getCompactRuns());
        m.put("archivedMessages", conv.getStats().getArchivedMessages());
        m.put("tokensSaved", conv.getStats().getTokensSavedEstimate());
        m.put("pinnedFacts", conv.getPinnedFacts());
        m.put("summaryPreview", preview(conv.getSummary(), 240));
        return m;
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
