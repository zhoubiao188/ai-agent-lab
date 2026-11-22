package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.LlmProvider;
import com.example.agent.llm.dto.LlmRequest;
import com.example.agent.llm.dto.Msg;
import com.example.agent.llm.dto.ToolCall;
import com.example.agent.observe.AgentMetrics;
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.ToolResult;
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
 * Agent 编排器 —— 整个项目的核心,就是一个 while 循环。
 *
 * <pre>
 * 用户输入
 *   └─> LLM(带工具声明) ──流式输出──> 前端
 *         ├─ 只回文本        ──> 结束
 *         └─ 要求调用工具    ──> 执行工具 ──> 结果写回消息列表 ──┐
 *                └──────────────────────────────────────┘(最多 maxSteps 轮)
 * </pre>
 *
 * 这里刻意不使用任何 Agent 框架:框架帮你省下的那几十行,恰恰是排查线上问题时
 * 最需要看懂的部分。自己写清楚每一步,加超时、加限步、加降级都很直接。
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final LlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory memory;
    private final AgentProperties props;
    private final AgentMetrics metrics;
    private final ObjectMapper mapper;

    /** 专门用于给工具执行加超时;工具是 IO 型,池子不用太大 */
    private final ExecutorService toolExecutor;

    public AgentOrchestrator(LlmProvider provider,
                             ToolRegistry toolRegistry,
                             ConversationMemory memory,
                             AgentProperties props,
                             AgentMetrics metrics,
                             ObjectMapper mapper) {
        this.provider = provider;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
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

    /** 供 /api/agent/info 展示 */
    public ProviderInfo info() {
        return new ProviderInfo(provider.name(), provider.realModel(), props.getLlm().getModel(),
                props.getMaxSteps(), toolRegistry.specs().stream().map(s -> s.name()).toList());
    }

    public record ProviderInfo(String provider, boolean realModel, String model, int maxSteps, List<String> tools) {
    }

    /**
     * 执行一轮对话。方法内部同步阻塞(由 Controller 放到线程池里跑),
     * 所有中间态通过 {@link AgentEventSink} 推给前端。
     */
    public void run(String conversationId, String userId, String userText, AgentEventSink sink) {
        metrics.streamOpened();
        long startedAt = System.nanoTime();
        int steps = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        String result = "ok";
        try {
            sink.emit("message_start", Map.of(
                    "conversationId", conversationId,
                    "provider", provider.name(),
                    "model", props.getLlm().getModel(),
                    "realModel", provider.realModel()));

            memory.append(conversationId, Msg.user(userText));

            for (steps = 1; steps <= props.getMaxSteps(); steps++) {
                if (sink.closed()) {
                    result = "client_closed";
                    return;
                }

                StepOutcome outcome = runOneStep(conversationId, sink);
                promptTokens += outcome.usage.promptTokens();
                completionTokens += outcome.usage.completionTokens();

                if (!outcome.hasToolCalls()) {
                    String answer = outcome.content.toString();
                    memory.append(conversationId, Msg.assistant(answer, null));
                    sink.emit("message_end", Map.of(
                            "content", answer,
                            "steps", steps,
                            "finishReason", outcome.finishReason == null ? "stop" : outcome.finishReason));
                    break;
                }

                // 关键细节:模型可能在同一步里先吐一段"我要查一下…"再发起工具调用。
                // 这段文本已经流给前端了,必须让它撤回,否则用户会看到重复的过渡语。
                if (!outcome.content.isEmpty()) {
                    sink.emit("delta_reset", Map.of("reason", "tool_call_started"));
                }

                memory.append(conversationId, Msg.assistant(
                        outcome.content.isEmpty() ? null : outcome.content.toString(), outcome.toolCalls));

                for (ToolCall call : outcome.toolCalls) {
                    if (sink.closed()) {
                        result = "client_closed";
                        return;
                    }
                    ToolResult tr = executeTool(call, conversationId, userId);
                    sink.emit("tool_result", Map.of(
                            "id", call.id(),
                            "name", call.name(),
                            "arguments", call.arguments(),
                            "success", tr.success(),
                            "content", tr.content(),
                            "elapsedMs", tr.elapsedMs()));
                    memory.append(conversationId, Msg.tool(call.id(), call.name(), tr.content()));
                }

                if (steps == props.getMaxSteps()) {
                    // 步数耗尽仍在调工具:不再给模型机会,直接兜底,避免无限烧 token
                    String fallback = "抱歉,这个问题需要更多步骤才能处理完,我已经停止继续调用工具。"
                            + "你可以把问题拆小一些再问我,或者回复「转人工」由客服跟进。";
                    memory.append(conversationId, Msg.assistant(fallback, null));
                    sink.emit("message_end", Map.of("content", fallback, "steps", steps, "finishReason", "max_steps"));
                    result = "max_steps";
                }
            }

            sink.emit("done", Map.of(
                    "steps", Math.min(steps, props.getMaxSteps()),
                    "promptTokens", promptTokens,
                    "completionTokens", completionTokens,
                    "elapsedMs", (System.nanoTime() - startedAt) / 1_000_000));
        } catch (Exception e) {
            result = "error";
            log.error("Agent 执行失败 conversationId={}", conversationId, e);
            sink.emit("error", Map.of(
                    "message", Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()),
                    "type", e.getClass().getSimpleName()));
        } finally {
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

    private StepOutcome runOneStep(String conversationId, AgentEventSink sink) {
        StepOutcome outcome = new StepOutcome();
        List<Msg> messages = buildMessages(conversationId);
        LlmRequest request = new LlmRequest(props.getLlm().getModel(), messages,
                toolRegistry.specs(), props.getLlm().getTemperature());

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
                    // name 只在第一帧出现,出现即代表这是一个新的工具调用
                    if (acc.name == null) {
                        acc.name = name;
                        sink.emit("tool_start", Map.of("index", index, "id", acc.id == null ? "" : acc.id, "name", name));
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
                throw new AgentException("LLM 调用失败: " + error.getMessage(), error);
            }
        });
        metrics.recordLlmLatency(System.nanoTime() - t0);

        outcome.toolCalls = outcome.accs.values().stream()
                .sorted(Comparator.comparingInt(a -> a.index))
                .filter(a -> a.name != null)
                .map(a -> new ToolCall(
                        a.id == null ? "call_" + a.index : a.id,
                        a.name,
                        a.args.length() == 0 ? "{}" : a.args.toString()))
                .toList();
        return outcome;
    }

    private List<Msg> buildMessages(String conversationId) {
        List<Msg> history = memory.history(conversationId);
        List<Msg> messages = new ArrayList<>(history.size() + 1);
        messages.add(Msg.system(systemPrompt()));
        messages.addAll(history);
        return messages;
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder(props.getPrompt().getSystem());
        if (props.getPrompt().isInjectToolCatalog() && toolRegistry.size() > 0) {
            sb.append("\n可用工具清单:\n");
            toolRegistry.specs().forEach(s -> sb.append("- ").append(s.name())
                    .append(": ").append(s.description()).append('\n'));
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
                // 工具超时必须小于整体请求超时,否则一个卡死的三方接口会拖垮整条链路
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
            // 模型偶尔会吐坏 JSON,不要抛出去,交给工具自己按"参数缺失"处理
            log.warn("工具参数不是合法 JSON: {}", raw);
            return mapper.createObjectNode();
        }
    }

    /** 编排层内部异常,统一被 run() 捕获并转成 error 事件 */
    public static class AgentException extends RuntimeException {
        public AgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
