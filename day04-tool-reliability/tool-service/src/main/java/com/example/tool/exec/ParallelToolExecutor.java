package com.example.tool.exec;

import com.example.tool.agent.AgentEvent;
import com.example.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 并行工具执行器(按依赖就绪调度,不是简单分层)。
 *
 * <p>第一版我写的是分层:把调用按依赖深度分成 wave1 / wave2 / wave3,一层全部跑完再下一层。
 * 结果实测比串行只快了 40%,原因是"陪跑"——wave1 里最慢的物流查询 200ms,
 * 而它后面的报价单其实只依赖订单查询(120ms),却要等物流跑完才能开始。
 * 改成依赖就绪调度(谁的依赖齐了谁就跑)之后,墙钟时间从 470ms 降到 330ms。</p>
 *
 * <p>并发要设上限。工具下游是别人的服务,无上限并发等于把你的流量放大成对方的事故,
 * 而且自己这边也只是把线程池堵死。</p>
 */
@Component
public class ParallelToolExecutor {

    private static final Pattern REF = Pattern.compile("^\\$([A-Za-z0-9_]+)\\.([A-Za-z0-9_.]+)$");

    private final ToolInvoker invoker;
    private final ToolProperties props;
    private final ExecutorService scheduler = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-scheduler");
        t.setDaemon(true);
        return t;
    });

    public ParallelToolExecutor(ToolInvoker invoker, ToolProperties props) {
        this.invoker = invoker;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public record BatchResult(List<ToolResult> results, long wallMs, int waves,
                              int maxConcurrent, long serialEstimateMs) {
        public boolean allOk() {
            return results.stream().allMatch(ToolResult::ok);
        }

        public long degradedCount() {
            return results.stream().filter(ToolResult::fallback).count();
        }
    }

    public BatchResult execute(List<ToolCall> rawCalls, ExecutionContext ctx, RunPolicy policy) {
        long start = System.currentTimeMillis();
        List<ToolCall> calls = withImplicitDeps(rawCalls);
        if (!policy.parallel() || calls.size() <= 1) {
            return executeSerial(calls, ctx, policy, start);
        }
        return executeDag(calls, ctx, policy, start);
    }

    private BatchResult executeSerial(List<ToolCall> calls, ExecutionContext ctx, RunPolicy policy, long start) {
        Map<String, ToolResult> done = new LinkedHashMap<>();
        long serialEstimate = 0;
        for (ToolCall call : calls) {
            ToolResult result = runOne(call, ctx, policy, done);
            done.put(call.id(), result);
            serialEstimate += result.durationMs();
        }
        return new BatchResult(new ArrayList<>(done.values()), System.currentTimeMillis() - start,
                calls.size(), 1, serialEstimate);
    }

    private BatchResult executeDag(List<ToolCall> calls, ExecutionContext ctx, RunPolicy policy, long start) {
        Semaphore permits = new Semaphore(Math.max(1, props.parallel().maxConcurrency()));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Map<String, CompletableFuture<ToolResult>> futures = new HashMap<>();
        Map<String, ToolResult> done = new java.util.concurrent.ConcurrentHashMap<>();

        // 按依赖顺序遍历:planner 一般已经是拓扑序,这里再兜一层,防止模型乱序吐 call
        for (ToolCall call : topological(calls)) {
            CompletableFuture<?>[] deps = call.dependsOn().stream()
                    .map(futures::get)
                    .filter(java.util.Objects::nonNull)
                    .toArray(CompletableFuture[]::new);

            CompletableFuture<ToolResult> future = CompletableFuture.allOf(deps)
                    .handleAsync((v, err) -> {
                        // 依赖已经跑完并且写进了 done,这里才能判断"是不是被依赖拖死的"
                        ToolResult blocked = blockedByDependency(call, done);
                        if (blocked != null) {
                            if (ctx.sink() != null) {
                                ctx.sink().emit(AgentEvent.of("tool_skip", ctx.elapsedMs(), Map.of(
                                        "callId", call.id(), "tool", call.tool(),
                                        "reason", String.valueOf(blocked.error()))));
                            }
                            done.put(call.id(), blocked);
                            return blocked;
                        }
                        permits.acquireUninterruptibly();
                        int now = inFlight.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        try {
                            ToolResult result = runOne(call, ctx, policy, done);
                            done.put(call.id(), result);
                            return result;
                        } finally {
                            inFlight.decrementAndGet();
                            permits.release();
                        }
                    }, scheduler);
            futures.put(call.id(), future);
        }

        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            CompletableFuture<ToolResult> f = futures.get(call.id());
            results.add(f == null
                    ? ToolResult.skipped(call.id(), call.tool(), "调度失败", System.currentTimeMillis())
                    : f.join());
        }
        return new BatchResult(results, System.currentTimeMillis() - start,
                waves(calls), Math.max(peak.get(), 1), 0);
    }

    private ToolResult runOne(ToolCall call, ExecutionContext ctx, RunPolicy policy, Map<String, ToolResult> done) {
        ObjectNode resolved = resolveArgs(call, done);
        if (resolved == null) {
            return ToolResult.failed(call.id(), call.tool(), ErrorKind.BAD_ARGUMENT,
                    "参数里的 $call.field 引用无法解析(依赖缺失或字段不存在)", 0,
                    System.currentTimeMillis(), System.currentTimeMillis(), List.of(), List.of(), false);
        }
        ToolCall effective = call.withArgs(resolved);
        if (ctx.sink() != null) {
            ctx.sink().emit(AgentEvent.of("call_resolved", ctx.elapsedMs(),
                    Map.of("callId", call.id(), "tool", call.tool(), "args", resolved)));
        }
        ExecutionContext callCtx = ctx.withCall(call.id(), ctx.requestId() + ":" + call.id());
        return invoker.invoke(effective, callCtx, policy);
    }

    private ToolResult blockedByDependency(ToolCall call, Map<String, ToolResult> done) {
        for (String dep : call.dependsOn()) {
            ToolResult r = done.get(dep);
            if (r != null && !r.ok()) {
                return ToolResult.skipped(call.id(), call.tool(),
                        "依赖 " + dep + " 失败(" + r.errorKind() + "),跳过", System.currentTimeMillis());
            }
        }
        return null;
    }

    /** 把 {@code $c1.paidAmount} 换成上游结果里的真实值 */
    private ObjectNode resolveArgs(ToolCall call, Map<String, ToolResult> done) {
        if (!call.args().toString().contains("$")) {
            return call.args();
        }
        ObjectNode copy = call.args().deepCopy();
        List<String> fields = new ArrayList<>();
        copy.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode value = copy.get(field);
            if (!value.isTextual()) {
                continue;
            }
            Matcher m = REF.matcher(value.asText());
            if (!m.matches()) {
                continue;
            }
            ToolResult source = done.get(m.group(1));
            if (source == null || source.payload() == null) {
                return null;
            }
            String path = m.group(2);
            JsonNode target = source.payload();
            for (String segment : path.split("\\.")) {
                target = target == null ? null : target.get(segment);
            }
            if (target == null) {
                return null;
            }
            copy.set(field, target);
        }
        return copy;
    }

    /** 缺失的显式依赖用参数里的引用补齐 */
    private List<ToolCall> withImplicitDeps(List<ToolCall> calls) {
        Set<String> ids = new LinkedHashSet<>();
        calls.forEach(c -> ids.add(c.id()));
        List<ToolCall> out = new ArrayList<>();
        for (ToolCall call : calls) {
            Set<String> deps = new LinkedHashSet<>(call.dependsOn());
            for (String id : ids) {
                if (!id.equals(call.id()) && call.references(id)) {
                    deps.add(id);
                }
            }
            out.add(call.withDependsOn(new ArrayList<>(deps)));
        }
        return out;
    }

    private List<ToolCall> topological(List<ToolCall> calls) {
        List<ToolCall> remaining = new ArrayList<>(calls);
        List<ToolCall> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < calls.size() * calls.size() + 1) {
            for (int i = 0; i < remaining.size(); i++) {
                ToolCall c = remaining.get(i);
                if (placed.containsAll(c.dependsOn())) {
                    ordered.add(c);
                    placed.add(c.id());
                    remaining.remove(i);
                    break;
                }
            }
        }
        // 有环(模型可能吐出互相引用):剩下的按原顺序追加,依赖方会因取不到结果而快速失败
        ordered.addAll(remaining);
        return ordered;
    }

    private int waves(List<ToolCall> calls) {
        Map<String, Integer> depth = new HashMap<>();
        for (ToolCall c : topological(calls)) {
            int d = 0;
            for (String dep : c.dependsOn()) {
                d = Math.max(d, depth.getOrDefault(dep, 0) + 1);
            }
            depth.put(c.id(), d);
        }
        return depth.values().stream().max(Integer::compareTo).orElse(0) + 1;
    }
}
