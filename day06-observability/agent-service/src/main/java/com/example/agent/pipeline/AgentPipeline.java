package com.example.agent.pipeline;

import com.example.agent.config.AgentProperties;
import com.example.agent.fault.FaultInjector;
import com.example.agent.fault.FaultPlan;
import com.example.agent.fault.Scenario;
import com.example.agent.metrics.RunMetrics;
import com.example.agent.tool.CircuitBreaker;
import com.example.agent.tool.ErrorKind;
import com.example.agent.tool.RunContext;
import com.example.agent.tool.ToolCallContext;
import com.example.agent.tool.ToolException;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tool.ToolResult;
import com.example.agent.tool.ToolSpec;
import com.example.o11y.attr.AttrKeys;
import com.example.o11y.decision.Basis;
import com.example.o11y.decision.Decision;
import com.example.o11y.decision.DecisionPoint;
import com.example.o11y.span.IntegrityGrade;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.Trace;
import com.example.o11y.trace.Tracer;
import com.example.o11y.triage.TriageEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个"能被看懂"的 Agent 流水线:20 个决策点全部留痕,5 类故障可按剧本注入。
 *
 * <p>这个类刻意写得像"业务代码",而不是像框架:每一步先做决定、再执行、决定写台账。
 * 现实里大家的代码也是这么写的,只是没写台账。本文要证明的是:补上台账的成本不高
 * (每个决策点多 5~8 行),但它把"排障"从翻日志变成了看三个嫌疑。</p>
 */
public class AgentPipeline {

    private static final Logger log = LoggerFactory.getLogger(AgentPipeline.class);

    private final Tracer tracer;
    private final ToolRegistry registry;
    private final SlotBinder binder;
    private final FaultInjector faults;
    private final AgentProperties props;
    private final TriageEngine triage;
    private final RunMetrics metrics;
    private final CircuitBreaker breaker;
    private final ExecutorService pool;
    private final IntentParser intentParser = new IntentParser();

    public AgentPipeline(Tracer tracer, ToolRegistry registry, SlotBinder binder, FaultInjector faults,
                         AgentProperties props, TriageEngine triage, RunMetrics metrics,
                         CircuitBreaker breaker, ExecutorService pool) {
        this.tracer = tracer;
        this.registry = registry;
        this.binder = binder;
        this.faults = faults;
        this.props = props;
        this.triage = triage;
        this.metrics = metrics;
        this.breaker = breaker;
        this.pool = pool;
    }

    public RunResult run(String question, Scenario scenario, String userId, String taskId) {
        FaultPlan plan = faults.plan(scenario, taskId);
        RunContext ctx = new RunContext(scenario, plan, tracer.clock(), taskId);
        Trace trace = tracer.begin(taskId, scenario.name(), question, userId)
                .attr(AttrKeys.AGENT_SCENARIO, scenario.name())
                .attr(AttrKeys.AGENT_TASK_ID, taskId);

        String answer = "";
        double trust = 0;
        Outcome outcome = Outcome.FAILED;

        try {
            IntentParser.IntentResult intent = decideIntent(question);
            SlotBinder.BindResult bind = decideSlots(question, userId, plan, intent);
            List<ToolRegistry.Candidate> candidates = decideRecall(question, intent, props.getRecallTopK());
            String primaryTool = decideSelect(intent, candidates);
            decideArgCoerce(bind, question, intent.intent(), ctx);
            decideClarify(bind, intent);
            decideRetrieveScope(plan);
            decideMemory(bind, question);

            List<List<PlannedStep>> layers = buildPlan(intent.intent(), bind.slots(), primaryTool);
            decidePlanShape(intent.intent(), layers);
            decideDependencies(layers);
            decideConcurrency(layers);
            long stepBudget = decideBudget(layers);
            decideIdempotency(layers, taskId);

            Execution execution = execute(layers, ctx, trace, stepBudget);
            SelfCheck check = selfCheck(intent.intent(), bind.slots(), execution);

            int requeryRounds = decideRequery(check, plan, layers, ctx, trace, stepBudget, execution);
            answer = synthesizeAnswer(question, intent.intent(), bind.slots(), execution, requeryRounds, ctx, trace);
            outcome = decideStop(check, execution);
            trust = check.trust();
        } catch (RuntimeException e) {
            log.warn("run failed task={} scenario={}", taskId, scenario, e);
            answer = "本次处理失败:" + e.getMessage();
            outcome = Outcome.FAILED;
            trust = 0.1;
            try (Tracer.Scope s = tracer.span("pipeline.error", SpanKind.INTERNAL)) {
                s.error(e.getClass().getSimpleName(), e.getMessage());
            }
        } finally {
            trace.answer(answer).trust(trust).outcome(outcome);
            tracer.finish(trace, outcome);
        }

        TriageEngine.Triage result = triage.triage(trace, 3);
        trace.attr("o11y.top.suspect", result.suspects().isEmpty() ? "-" : result.suspects().get(0).name());
        if (metrics != null) {
            metrics.record(trace, result);
        }
        return new RunResult(trace.traceId(), answer, outcome, trust, result,
                ctx.culpritSpanId(), ctx.culpritReason());
    }

    // ---- D01 意图 ----

    private IntentParser.IntentResult decideIntent(String question) {
        IntentParser.IntentResult result = intentParser.parse(question);
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D01_INTENT)) {
            Decision.Builder b = Decision.at(DecisionPoint.D01_INTENT)
                    .chosen(result.intent().label())
                    .basis(Basis.RULE)
                    .confidence(result.confidence())
                    .attr("question.length", question.length());
            result.scores().forEach((name, score) ->
                    b.alternative(labelOf(name), score, score > 0 ? "关键词命中" : "无命中"));
            d.record(b.build());
        }
        return result;
    }

    private static String labelOf(String intentName) {
        try {
            return Intent.valueOf(intentName).label();
        } catch (IllegalArgumentException e) {
            return intentName;
        }
    }

    // ---- D02 槽位盘点 ----

    private SlotBinder.BindResult decideSlots(String question, String userId, FaultPlan plan,
                                              IntentParser.IntentResult intent) {
        SlotBinder.BindResult bind = binder.bind(question, userId, plan, intent.intent());
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D02_SLOT_AUDIT)) {
            boolean noSlots = bind.required().isEmpty();
            boolean ok = bind.defaults().isEmpty();
            Decision.Builder b = Decision.at(DecisionPoint.D02_SLOT_AUDIT)
                    .chosen(noSlots ? "本意图无需槽位" : ok ? "槽位齐全"
                            : "缺 " + bind.defaults().size() + " 个,默认值兜底")
                    .basis(ok ? Basis.RULE : Basis.DEFAULT)
                    .confidence(bind.confidence())
                    .attr("slots", bind.slots().values())
                    .alternative(noSlots ? "本意图无需槽位" : ok ? "槽位齐全" : "缺 " + bind.defaults().size() + " 个,默认值兜底",
                            ok ? loadedScore(bind) : 0.72, ok ? "全部来自原话或记忆" : "默认值让流程看起来成功")
                    .alternative("向用户追问缺失槽位", 0.9, "需要一次额外往返");
            d.record(b.build());
        }
        return bind;
    }

    private double loadedScore(SlotBinder.BindResult bind) {
        return bind.slots().values().isEmpty() ? 0.5 : bind.confidence();
    }

    // ---- D03 工具召回 ----

    private List<ToolRegistry.Candidate> decideRecall(String question, IntentParser.IntentResult intent, int topK) {
        Map<String, Double> affinity = new LinkedHashMap<>();
        for (String tool : intent.intent().preferredTools()) {
            affinity.put(tool, 1.0);
        }
        List<ToolRegistry.Candidate> candidates = registry.recall(question, intent.intent().name(), affinity, topK);
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D03_TOOL_RECALL)) {
            boolean covered = candidates.stream().anyMatch(c -> affinity.containsKey(c.tool()));
            Decision.Builder b = Decision.at(DecisionPoint.D03_TOOL_RECALL)
                    .chosen("top-" + candidates.size() + " 候选")
                    .basis(Basis.HEURISTIC)
                    .confidence(covered ? 0.85 : 0.3)
                    .attr("candidate.count", candidates.size())
                    .attr("registry.size", registry.size());
            candidates.forEach(c -> b.alternative(c.tool(), c.score(), c.hits() + " 个关键词命中"));
            if (!covered) {
                b.alternative("必需的 " + intent.intent().preferredTools().get(0) + " 未召回", 0.0,
                        "召回按关键词打分,业务必需工具没有强制兜底");
            }
            d.record(b.build());
        }
        return candidates;
    }

    // ---- D04 工具选择 ----

    /**
     * 主工具选择。这里刻意不用"得分最高"当唯一标准:关键词打分很容易让
     * {@code refund.commit}(关键词含"退款/提交/处理")排第一,但真正该先做的是查订单。
     * 业务上必需的工具优先级高于文本相似度,所以先按意图的首选顺序挑,再退回分数。
     */
    private String decideSelect(IntentParser.IntentResult intent, List<ToolRegistry.Candidate> candidates) {
        List<String> present = candidates.stream().map(ToolRegistry.Candidate::tool).toList();
        String chosen = intent.intent().preferredTools().stream()
                .filter(present::contains).findFirst()
                .orElse(candidates.isEmpty() ? "doc.search" : candidates.get(0).tool());
        double chosenScore = candidates.stream().filter(c -> c.tool().equals(chosen))
                .mapToDouble(ToolRegistry.Candidate::score).findFirst().orElse(0);
        double bestOther = candidates.stream().filter(c -> !c.tool().equals(chosen))
                .mapToDouble(ToolRegistry.Candidate::score).max().orElse(0);
        double margin = chosenScore <= 0 ? 0 : (chosenScore - bestOther) / chosenScore;
        double confidence = 0.75 + 0.2 * Math.max(0, Math.min(1, margin));
        if (chosenScore <= 0) {
            confidence = 0.25;
        }
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D04_TOOL_SELECT)) {
            Decision.Builder b = Decision.at(DecisionPoint.D04_TOOL_SELECT)
                    .chosen(chosen)
                    .basis(Basis.HEURISTIC)
                    .confidence(Math.round(confidence * 100) / 100.0)
                    .attr("selection.rule", "意图首选工具优先于文本得分");
            candidates.forEach(c -> b.alternative(c.tool(), c.score(),
                    c.tool().equals(chosen) ? "意图首选工具" : "文本得分较高但业务优先级低"));
            d.record(b.build());
        }
        return chosen;
    }

    // ---- D05 参数来源 ----

    private void decideArgCoerce(SlotBinder.BindResult bind, String question, Intent intent, RunContext ctx) {
        List<String> defaults = bind.defaults();
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D05_ARG_BIND)) {
            Decision.Builder b = Decision.at(DecisionPoint.D05_ARG_BIND)
                    .chosen(bind.required().isEmpty() ? "无需绑定" : defaults.isEmpty() ? "全部来自原话" : "默认值兜底")
                    .basis(defaults.isEmpty() ? Basis.RULE : Basis.DEFAULT)
                    .confidence(bind.confidence())
                    .attr("slot.sources", bind.slots().sources())
                    .attr("slot.defaulted", defaults.size());
            b.alternative(bind.required().isEmpty() ? "无需绑定" : "全部来自原话",
                    bind.required().isEmpty() ? 0.96 : bind.slots().filledByText(), "用户明确给出");
            b.alternative("默认值兜底", defaults.size(), "缺失槽位用 0 / 固定值填充");
            b.alternative("追问用户", 0.9, "需要一次额外往返,自动模式下被跳过");
            d.record(b.build());
            if (intent == Intent.REFUND && defaults.contains("amountCents")) {
                // 关键槽位走了默认值,这就是本次运行最可疑的一次决策。
                // 埋点只记位置,排序算法依然只能靠信号自己找。
                ctx.noteCulprit(d.spanId(), "缺金额时用默认值 0 元兜底");
            }
        }

        Integer amount = bind.slots().integer("amountCents");
        boolean fromText = Slots.TEXT.equals(bind.slots().source("amountCents"));
        double confidence = amount == null ? 0.2 : fromText ? 0.9 : 0.3;
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D06_ARG_COERCE)) {
            d.record(Decision.at(DecisionPoint.D06_ARG_COERCE)
                    .chosen(amount == null ? "未识别到金额" : "金额 " + (amount / 100.0) + " 元")
                    .basis(fromText ? Basis.RULE : Basis.DEFAULT)
                    .confidence(confidence)
                    .attr("slot.amountCents", amount)
                    .attr("slot.source", bind.slots().source("amountCents"))
                    .alternative("按元解析", amount == null ? 0 : (amount / 100.0), "带单位或「退 X」结构")
                    .alternative("按分解析", amount == null ? 0 : amount, "用户直接给最小单位")
                    .build());
        }

    }

    // ---- D07 澄清 ----

    private void decideClarify(SlotBinder.BindResult bind, IntentParser.IntentResult intent) {
        boolean shouldAsk = bind.confidence() < 0.55;
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D07_CLARIFY)) {
            d.record(Decision.at(DecisionPoint.D07_CLARIFY)
                    .chosen(shouldAsk ? "按默认值继续" : "无需澄清,直接执行")
                    .basis(shouldAsk ? Basis.FALLBACK : Basis.RULE)
                    .confidence(shouldAsk ? 0.45 : 0.92)
                    .attr("intent.confidence", intent.confidence())
                    .attr("slot.confidence", bind.confidence())
                    .alternative("追问用户", 0.88, "准确但多一轮往返,自动模式下不可用")
                    .alternative("无需澄清,直接执行", shouldAsk ? 0.2 : 0.92, "槽位置信度足够")
                    .build());
        }
    }

    // ---- D08 检索范围 ----

    private void decideRetrieveScope(FaultPlan plan) {
        boolean verbose = plan.verboseDocs();
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D08_RETRIEVE_SCOPE)) {
            d.record(Decision.at(DecisionPoint.D08_RETRIEVE_SCOPE)
                    .chosen(verbose ? "全量 40 条片段" : "top-3 片段")
                    .basis(verbose ? Basis.HEURISTIC : Basis.RULE)
                    .confidence(verbose ? 0.4 : 0.9)
                    .attr("scope.size", verbose ? 40 : 3)
                    .alternative("top-3", 0.9, "覆盖常见问题,上下文可控")
                    .alternative("全量 40 条", verbose ? 0.85 : 0.15, "召回更全,但 prompt 体积失控")
                    .build());
        }
    }

    // ---- D09 记忆 ----

    private void decideMemory(SlotBinder.BindResult bind, String question) {
        boolean fromMemory = bind.slots().sources().containsValue(Slots.MEMORY);
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D09_MEMORY_RECALL)) {
            d.record(Decision.at(DecisionPoint.D09_MEMORY_RECALL)
                    .chosen(fromMemory ? "命中上一轮订单号" : "不召回历史")
                    .basis(fromMemory ? Basis.MEMORY : Basis.RULE)
                    .confidence(fromMemory ? 0.8 : 0.9)
                    .attr("question.has.reference", question.contains("刚才") || question.contains("这个订单"))
                    .alternative("召回最近订单", fromMemory ? 0.8 : 0.1, "省一次追问")
                    .alternative("不召回", fromMemory ? 0.2 : 0.9, "避免串单")
                    .build());
        }
    }

    // ---- D10 计划形态 / D11 依赖 / D12 并发 / D13 预算 ----

    private List<List<PlannedStep>> buildPlan(Intent intent, Slots slots, String primaryTool) {
        String orderId = slots.string("orderId");
        int amount = slots.integer("amountCents") == null ? 0 : slots.integer("amountCents");
        String reason = slots.string("reason");
        List<List<PlannedStep>> layers = new ArrayList<>();

        switch (intent) {
            case REFUND -> {
                layers.add(layer(step(1, "order.get", args("orderId", orderId), "无")));
                layers.add(layer(
                        step(2, "price.calc", args("orderId", orderId), "order.get"),
                        step(3, "policy.lookup", args("topic", "退款"), "order.get")));
                layers.add(layer(step(4, "refund.preview",
                        args("orderId", orderId, "amountCents", amount), "price.calc")));
                layers.add(layer(step(5, "refund.commit",
                        args("orderId", orderId, "amountCents", amount, "reason", reason), "refund.preview")));
            }
            case QUERY_ORDER -> {
                layers.add(layer(step(1, "order.get", args("orderId", orderId), "无")));
                layers.add(layer(step(2, "log.query", args("orderId", orderId), "order.get")));
            }
            case STOCK -> layers.add(layer(step(1, "inventory.get",
                    args("sku", slots.string("sku") == null ? "SKU-201" : slots.string("sku")), "无")));
            case TICKET -> {
                layers.add(layer(step(1, "order.get", args("orderId", orderId), "无")));
                layers.add(layer(step(2, "ticket.create",
                        args("orderId", orderId, "summary", reason + " 售后工单"), "order.get")));
            }
            case DOC -> layers.add(layer(step(1, "doc.search",
                    args("query", "退款政策", "topK", 3), "无")));
            case AFTERSALE -> {
                layers.add(layer(step(1, "order.get", args("orderId", orderId), "无")));
                layers.add(layer(step(2, "doc.search", args("query", "换货政策", "topK", 3), "order.get")));
            }
        }
        if (layers.isEmpty()) {
            layers.add(layer(step(1, primaryTool, Map.of(), "无")));
        }
        return layers;
    }

    /**
     * 层必须是可变列表:带写操作的步骤稍后要补幂等键。
     * 用 List.of 会在补键时抛 UnsupportedOperationException —— 而且是运行时才炸。
     */
    private List<PlannedStep> layer(PlannedStep... steps) {
        return new ArrayList<>(List.of(steps));
    }

    private PlannedStep step(int index, String tool, Map<String, Object> args, String dependsOn) {
        return new PlannedStep(index, tool, args, dependsOn);
    }

    private Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private void decidePlanShape(Intent intent, List<List<PlannedStep>> layers) {
        int steps = layers.stream().mapToInt(List::size).sum();
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D10_PLAN_SHAPE)) {
            d.record(Decision.at(DecisionPoint.D10_PLAN_SHAPE)
                    .chosen(steps == 1 ? "单步直出" : steps + " 步 / " + layers.size() + " 层")
                    .basis(Basis.RULE)
                    .confidence(steps <= 6 ? 0.88 : 0.6)
                    .attr("step.count", steps)
                    .attr("layer.count", layers.size())
                    .alternative("单步直出", steps == 1 ? 0.9 : 0.55, "延迟最低,但退款必须查—算—写")
                    .alternative(steps + " 步", steps == 1 ? 0.3 : 0.88, "每步都有明确输入输出")
                    .build());
        }
    }

    private void decideDependencies(List<List<PlannedStep>> layers) {
        long parallelizable = layers.stream().filter(l -> l.size() > 1).mapToLong(List::size).sum();
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D11_STEP_DEPENDENCY)) {
            d.record(Decision.at(DecisionPoint.D11_STEP_DEPENDENCY)
                    .chosen(parallelizable > 0 ? parallelizable + " 步可并行" : "全串行")
                    .basis(Basis.RULE)
                    .confidence(0.93)
                    .attr("parallelizable", parallelizable)
                    .alternative("全串行", 0.5, "实现简单,延迟是加法")
                    .alternative("按 dependsOn 分层", parallelizable > 0 ? 0.93 : 0.5, "同层无依赖,可并发")
                    .build());
        }
    }

    private void decideConcurrency(List<List<PlannedStep>> layers) {
        int maxWidth = layers.stream().mapToInt(List::size).max().orElse(1);
        int limit = Math.min(maxWidth, props.getParallelism());
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D12_CONCURRENCY)) {
            d.record(Decision.at(DecisionPoint.D12_CONCURRENCY)
                    .chosen("并发上限 " + limit)
                    .basis(Basis.RULE)
                    .confidence(0.9)
                    .attr("layer.width", maxWidth)
                    .attr("pool.size", props.getParallelism())
                    .alternative("串行", 0.4, "无并发风险,延迟高")
                    .alternative("上限 " + limit, 0.9, "不超过下游限流阈值")
                    .build());
        }
    }

    private long decideBudget(List<List<PlannedStep>> layers) {
        int steps = layers.stream().mapToInt(List::size).sum();
        long budget = Math.max(props.getToolTimeoutMs(), props.getStepBudgetMs());
        long perStep = Math.min(budget, Math.max(props.getToolTimeoutMs(), props.getRunBudgetMs() / Math.max(1, steps)));
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D13_BUDGET)) {
            d.record(Decision.at(DecisionPoint.D13_BUDGET)
                    .chosen("每步 " + perStep + "ms")
                    .basis(Basis.RULE)
                    .confidence(0.85)
                    .attr("run.budget.ms", props.getRunBudgetMs())
                    .attr("step.budget.ms", perStep)
                    .attr("tool.timeout.ms", props.getToolTimeoutMs())
                    .alternative("平均分", props.getRunBudgetMs() / Math.max(1, steps), "简单,慢工具会挤死快工具")
                    .alternative("按 P99 分", perStep, "慢调用有自己的预算")
                    .build());
        }
        return perStep;
    }

    private void decideIdempotency(List<List<PlannedStep>> layers, String taskId) {
        AtomicInteger writes = new AtomicInteger();
        for (List<PlannedStep> layer : layers) {
            for (int i = 0; i < layer.size(); i++) {
                PlannedStep s = layer.get(i);
                if (registry.find(s.tool()).map(ToolSpec::write).orElse(false)) {
                    Map<String, Object> args = new LinkedHashMap<>(s.args());
                    // 幂等键必须带参数哈希:只带 taskId:stepId 时,重规划后"正确金额"的请求
                    // 会被第一次"错误金额"的请求顶回去(Day05 踩过的坑)
                    args.put("idempotencyKey", taskId + ":" + s.index() + ":" + s.argHash());
                    layer.set(i, new PlannedStep(s.index(), s.tool(), args, s.dependsOn()));
                    writes.incrementAndGet();
                }
            }
        }
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D18_IDEMPOTENCY)) {
            d.record(Decision.at(DecisionPoint.D18_IDEMPOTENCY)
                    .chosen(writes.get() == 0 ? "本次无写操作" : writes.get() + " 个写操作带幂等键")
                    .basis(Basis.RULE)
                    .confidence(0.95)
                    .attr("write.count", writes.get())
                    .alternative("taskId:stepId", 0.4, "重规划后参数变了但键没变,会吞掉正确请求")
                    .alternative("taskId:stepId:hash(args)", 0.95, "参数变了就是新请求")
                    .build());
        }
    }

    // ---- 执行 ----

    private record Execution(Map<Integer, StepOutcome> outcomes, List<PlannedStep> steps,
                             List<String> docTexts) {

        StepOutcome of(int index) {
            return outcomes.get(index);
        }

        StepOutcome ofTool(String tool) {
            for (PlannedStep s : steps) {
                if (s.tool().equals(tool) && outcomes.containsKey(s.index())) {
                    return outcomes.get(s.index());
                }
            }
            return null;
        }

        boolean anyFallback() {
            return outcomes.values().stream().anyMatch(StepOutcome::fallback);
        }

        boolean allOk() {
            return outcomes.values().stream().allMatch(StepOutcome::ok);
        }

        int totalAttempts() {
            return outcomes.values().stream().mapToInt(StepOutcome::attempts).sum();
        }
    }

    private Execution execute(List<List<PlannedStep>> layers, RunContext ctx, Trace trace, long stepBudget) {
        Map<Integer, StepOutcome> outcomes = new ConcurrentHashMap<>();
        List<String> docTexts = new CopyOnWriteArrayList<>();
        List<PlannedStep> flat = new ArrayList<>();
        layers.forEach(flat::addAll);

        Span parent = tracer.currentSpan().orElse(trace.root());
        Tracer.Carrier carrier = tracer.capture();

        for (List<PlannedStep> layer : layers) {
            if (layer.size() == 1) {
                PlannedStep s = layer.get(0);
                try (Tracer.Scope scope = tracer.span("step " + s.index() + " " + s.tool(), SpanKind.STEP)) {
                    outcomes.put(s.index(), runStepInside(scope, s, ctx, stepBudget, docTexts));
                }
            } else {
                List<Future<?>> futures = new ArrayList<>();
                for (PlannedStep s : layer) {
                    futures.add(pool.submit(() -> {
                        try (Tracer.Scope scope = tracer.resume(carrier,
                                "step " + s.index() + " " + s.tool(), SpanKind.STEP)) {
                            outcomes.put(s.index(), runStepInside(scope, s, ctx, stepBudget, docTexts));
                        } finally {
                            tracer.detach();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        log.warn("并行步骤异常", e);
                    }
                }
            }
        }
        return new Execution(outcomes, flat, docTexts);
    }

    private StepOutcome runStepInside(Tracer.Scope stepScope, PlannedStep step, RunContext ctx,
                                      long stepBudget, List<String> docTexts) {
        ToolSpec spec = registry.require(step.tool());
        stepScope.tag(AttrKeys.STEP_INDEX, step.index())
                .tag(AttrKeys.STEP_TOOL, spec.name())
                .tag(AttrKeys.STEP_DEPENDS_ON, step.dependsOn());

        long deadline = tracer.clock().nanoTime() + stepBudget * 1_000_000L;
        int attempt = 0;
        ErrorKind lastError = null;

        while (attempt < props.getMaxAttempts()) {
            attempt++;
            long remainingMs = (deadline - tracer.clock().nanoTime()) / 1_000_000L;

            try (Tracer.Scope toolSpan = tracer.spanUnder(stepScope.span(),
                    (spec.write() ? "write " : "tool ") + spec.name(),
                    spec.write() ? SpanKind.WRITE : SpanKind.TOOL)) {
                toolSpan.tag(AttrKeys.TOOL_NAME, spec.name())
                        .tag(AttrKeys.TOOL_WRITE, spec.write())
                        .tag(AttrKeys.TOOL_ATTEMPT, attempt)
                        .tag(AttrKeys.TOOL_ARG_HASH, step.argHash())
                        .tag(AttrKeys.TOOL_ARG_SUMMARY, step.argSummary())
                        .attempts(attempt);

                if (!breaker.allow(spec.name())) {
                    toolSpan.event("breaker.open", Map.of("tool", spec.name()))
                            .tag("breaker.state", "OPEN");
                    lastError = ErrorKind.DEPENDENCY_FAILED;
                } else if (remainingMs <= 5) {
                    toolSpan.error(ErrorKind.TIMEOUT.name(), "步骤预算已耗尽");
                    lastError = ErrorKind.TIMEOUT;
                } else {
                    long timeout = Math.min(props.getToolTimeoutMs(), remainingMs);
                    toolSpan.tag(AttrKeys.TOOL_DEADLINE_MS, timeout);
                    ToolCallContext call = new ToolCallContext(ctx, spec.name(), attempt, timeout);
                    long start = tracer.clock().nanoTime();
                    try {
                        ToolResult result = spec.handler().call(step.args(), call);
                        long elapsed = (tracer.clock().nanoTime() - start) / 1_000_000L;
                        if (elapsed >= timeout) {
                            throw new ToolException(ErrorKind.TIMEOUT,
                                    "调用耗时 " + elapsed + "ms 超过预算 " + timeout + "ms");
                        }
                        if (Boolean.FALSE.equals(result.data().get("allowed"))) {
                            // 业务试算给了否定结论:接口是成功的,结论是"不允许"。
                            // 这种"成功的失败"如果只看 error 状态,永远发现不了。
                            // 但它是**护栏**不是现场:预演没有改状态,坏的是它收到的参数。
                            toolSpan.integrity(false, "业务试算未通过:" + result.data().get("reason"),
                                    IntegrityGrade.CAUGHT);
                        }
                        inspectWrite(toolSpan, spec, result, step, ctx);
                        toolSpan.tag(AttrKeys.TOOL_RESULT_SUMMARY, result.summary());
                        if (isDocTool(spec.name())) {
                            collectDocs(result, docTexts);
                        }
                        stepScope.tag("step.result", result.summary());
                        breaker.recordSuccess(spec.name());
                        return result.stale()
                                ? StepOutcome.degraded(result.merge(), result.summary(), attempt)
                                : StepOutcome.ok(result.merge(), result.summary(), attempt);
                    } catch (ToolException e) {
                        toolSpan.error(e.kind().name(), e.getMessage())
                                .tag(AttrKeys.TOOL_RETRYABLE, e.kind().retryable());
                        lastError = e.kind();
                        breaker.recordFailure(spec.name());
                        // 评测标注:这个工具在本次运行里被注入了故障,记下埋点位置。
                        // 排序算法拿不到 RunContext,所以这不影响"检测"的公平性。
                        if (ctx.plan().isFaultTarget(spec.name())) {
                            ctx.noteCulprit(toolSpan.spanId(), "注入故障命中 " + spec.name());
                        }
                    }
                }
            }

            boolean retry = lastError != null && lastError.retryable()
                    && attempt < props.getMaxAttempts()
                    && (deadline - tracer.clock().nanoTime()) / 1_000_000L > props.getBackoffBaseMs();
            decideRetry(step, attempt, lastError, retry);
            if (!retry) {
                break;
            }
            long backoff = Math.min(props.getBackoffBaseMs() * (1L << (attempt - 1)), props.getBackoffMaxMs());
            try (Tracer.Scope retrySpan = tracer.spanUnder(stepScope.span(),
                    "retry " + attempt + " " + spec.name(), SpanKind.RETRY)) {
                retrySpan.tag(AttrKeys.RETRY_DELAY_MS, backoff)
                        .tag(AttrKeys.RETRY_REASON, lastError == null ? "unknown" : lastError.name())
                        .event("backoff", Map.of("ms", backoff));
                tracer.clock().sleep(backoff);
            }
        }

        // 走到这里说明重试也失败了:D15 降级
        boolean canFallback = spec.name().equals("order.get") || spec.name().equals("inventory.get")
                || spec.name().equals("price.calc");
        decideFallback(step, lastError, canFallback);
        if (canFallback) {
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("degraded", true);
            stale.put("stale", true);
            stale.put("tool", spec.name());
            stale.put("note", "使用 5 分钟前的缓存数据");
            stepScope.tag("step.degraded", true);
            return StepOutcome.degraded(stale, "降级:" + (lastError == null ? "未知" : lastError.label()), attempt);
        }
        stepScope.integrity(false, "步骤失败且无降级路径");
        return StepOutcome.failed(lastError == null ? ErrorKind.BUSINESS : lastError, attempt);
    }

    private void decideRetry(PlannedStep step, int attempt, ErrorKind error, boolean retry) {
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D14_RETRY)) {
            d.record(Decision.at(DecisionPoint.D14_RETRY)
                    .chosen(retry ? "重试(第 " + (attempt + 1) + " 次)" : "不重试,进入降级")
                    .basis(Basis.RULE)
                    .confidence(0.9)
                    .attr("attempt", attempt)
                    .attr("error.kind", error == null ? "UNKNOWN" : error.name())
                    .attr("error.retryable", error != null && error.retryable())
                    .alternative("重试", retry ? 0.9 : 0.3, "错误可重试且还有预算")
                    .alternative("直接降级", retry ? 0.3 : 0.9, "重试救不回来,别烧预算")
                    .build());
        }
    }

    private void decideFallback(PlannedStep step, ErrorKind error, boolean canFallback) {
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D15_FALLBACK)) {
            d.record(Decision.at(DecisionPoint.D15_FALLBACK)
                    .chosen(canFallback ? "返回缓存数据并标记 stale" : "向上返回失败")
                    .basis(Basis.FALLBACK)
                    .confidence(canFallback ? 0.55 : 0.8)
                    .attr("tool", step.tool())
                    .attr("error.kind", error == null ? "UNKNOWN" : error.name())
                    .alternative("缓存兜底", canFallback ? 0.55 : 0.2, "用户拿到旧数据,但流程不中断")
                    .alternative("直接报错", canFallback ? 0.4 : 0.8, "诚实,但可用性差")
                    .build());
        }
    }

    private boolean isDocTool(String tool) {
        return "doc.search".equals(tool);
    }

    @SuppressWarnings("unchecked")
    private void collectDocs(ToolResult result, List<String> docTexts) {
        Object docs = result.data().get("docs");
        if (docs instanceof List<?> list) {
            for (Object doc : list) {
                if (doc instanceof Map<?, ?> m) {
                    Object text = ((Map<String, Object>) m).get("text");
                    if (text != null) {
                        docTexts.add(String.valueOf(text));
                    }
                }
            }
        }
    }

    /**
     * 写操作后做三件事:比回显、记幂等键、看重复提交。
     *
     * <p>回显不一致**不会**让调用失败 —— 这是刻意的。真实系统里上游返回 200 就是 200,
     * 没有人会在这里抛异常。但我们把 {@code write.echo.match=false} 和 integrity 记下来,
     * 它就成了沉默失败唯一可被发现的入口。</p>
     */
    private void inspectWrite(Tracer.Scope toolSpan, ToolSpec spec, ToolResult result,
                              PlannedStep step, RunContext ctx) {
        if (!spec.write()) {
            return;
        }
        toolSpan.tag(AttrKeys.WRITE_IDEMPOTENCY_KEY, String.valueOf(step.args().get("idempotencyKey")));
        Object duplicate = result.echo().get("duplicate");
        toolSpan.tag(AttrKeys.WRITE_DUPLICATE_BLOCKED, Boolean.TRUE.equals(duplicate));

        Object sent = step.args().get("amountCents");
        Object echoed = result.echo().get("amountCents");
        if (sent != null && echoed != null) {
            boolean match = String.valueOf(sent).equals(String.valueOf(echoed));
            toolSpan.tag(AttrKeys.WRITE_ECHO_MATCH, match);
            if (!match) {
                toolSpan.integrity(false, "回显金额 " + echoed + " 与入参 " + sent + " 不一致");
                ctx.noteCulprit(toolSpan.spanId(), "写入回显与入参不一致");
            }
        }
    }

    // ---- D16 自检 ----

    /**
     * 自检结果。failures 是**阻断性**问题,有就是失败;warnings 是"能跑但不对"的信号,
     * 只扣可信度。这两类必须分开:早期版本把"证据过载"也当阻断,结果一次检索就把整个
     * 会话判死,用户体验比不做自检还差。
     */
    private record SelfCheck(List<String> failures, List<String> warnings,
                             double trust, Map<String, Object> facts) {

        boolean passed() {
            return failures.isEmpty();
        }
    }

    private SelfCheck selfCheck(Intent intent, Slots slots, Execution execution) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();

        Integer amount = slots.integer("amountCents");
        Integer paid = intOf(execution.ofTool("order.get"), "paidCents");
        facts.put("amountCents", amount);
        facts.put("paidCents", paid);

        if (intent == Intent.REFUND) {
            if (amount == null || amount <= 0) {
                failures.add("退款金额必须大于 0,当前 " + amount);
            }
            if (amount != null && paid != null && amount > paid) {
                failures.add("退款金额 " + amount + " 超过实付 " + paid);
            }
            StepOutcome commit = execution.ofTool("refund.commit");
            if (commit == null || !commit.ok()) {
                failures.add("退款写入未成功");
            } else if (Boolean.TRUE.equals(commit.data().get("echo.duplicate"))) {
                failures.add("写入被幂等拦截,可能重复提交");
            }
        }

        long docs = execution.docTexts().size();
        facts.put("doc.fragments", docs);
        if (docs > 20) {
            warnings.add("证据过载:" + docs + " 个片段,超出 20 的建议上限");
        }

        if (execution.anyFallback()) {
            facts.put("degraded", true);
        }

        double trust = 0.95;
        trust -= 0.35 * failures.size();
        trust -= 0.15 * warnings.size();
        if (execution.anyFallback()) {
            trust -= 0.2;
        }
        trust = Math.max(0.05, Math.round(trust * 100) / 100.0);
        facts.put("attempts", execution.totalAttempts());

        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D16_SELF_CHECK)) {
            d.record(Decision.at(DecisionPoint.D16_SELF_CHECK)
                    .chosen(!failures.isEmpty() ? "不通过" : warnings.isEmpty() ? "通过" : "通过但有告警")
                    .basis(Basis.RULE)
                    .confidence(!failures.isEmpty() ? 0.35 : warnings.isEmpty() ? 0.9 : 0.6)
                    .attr("failures", failures)
                    .attr("warnings", warnings)
                    .attr("failure.detail", failures.isEmpty() ? warnings : failures)
                    .attr("trust", trust)
                    .alternative("通过", failures.isEmpty() ? 0.9 : 0.2, "金额、上限、写入落地")
                    .alternative("不通过", failures.isEmpty() ? 0.25 : 0.85,
                            String.join(";", failures.isEmpty() ? warnings : failures))
                    .build());
        }
        return new SelfCheck(failures, warnings, trust, facts);
    }

    private Integer intOf(StepOutcome outcome, String key) {
        if (outcome == null) {
            return null;
        }
        Object v = outcome.data().get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    // ---- D17 补查 ----

    private int decideRequery(SelfCheck check, FaultPlan plan, List<List<PlannedStep>> layers,
                              RunContext ctx, Trace trace, long stepBudget, Execution execution) {
        boolean overloaded = java.util.stream.Stream.concat(check.failures().stream(), check.warnings().stream())
                .anyMatch(f -> f.startsWith("证据过载"));
        boolean needRequery = overloaded && plan.verboseDocs();
        int rounds = needRequery ? 2 : 0;

        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D17_REQUERY)) {
            d.record(Decision.at(DecisionPoint.D17_REQUERY)
                    .chosen(needRequery ? "补查 " + rounds + " 轮(未收窄范围)" : "证据够用,不补查")
                    .basis(Basis.HEURISTIC)
                    .confidence(needRequery ? 0.35 : 0.85)
                    .attr("rounds", rounds)
                    .attr("narrowed", false)
                    .alternative("收窄到 3 条再查", 0.85, "成本最低,够用")
                    .alternative("原样再查两轮", needRequery ? 0.6 : 0.1, "看起来更谨慎,实际只增加 token")
                    .build());
        }

        if (!needRequery) {
            return 0;
        }
        PlannedStep docStep = layers.stream().flatMap(List::stream)
                .filter(s -> s.tool().equals("doc.search")).findFirst()
                .orElse(new PlannedStep(90, "doc.search", Map.of("query", "退款政策", "topK", 3), "无"));
        for (int i = 1; i <= rounds; i++) {
            PlannedStep requery = new PlannedStep(90 + i, "doc.search",
                    Map.of("query", "退款政策", "topK", 40, "round", i), "doc.search");
            try (Tracer.Scope scope = tracer.span("step " + requery.index() + " doc.search(补查)", SpanKind.STEP)) {
                runStepInside(scope, requery, ctx, stepBudget, execution.docTexts());
            }
            trace.attr("requery.round." + i, execution.docTexts().size());
        }
        return rounds;
    }

    // ---- D19 回答 ----

    private String synthesizeAnswer(String question, Intent intent, Slots slots, Execution execution,
                                    int requeryRounds, RunContext ctx, Trace trace) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是售后助手。用户问题:").append(question).append('\n');
        prompt.append("已收集证据:\n");
        execution.outcomes().forEach((idx, outcome) -> prompt.append("- 步骤").append(idx)
                .append(':').append(outcome.summary()).append('\n'));
        for (String doc : execution.docTexts()) {
            prompt.append("片段:").append(doc).append('\n');
        }
        prompt.append("请基于上述证据回答,不要编造。");

        String answer = buildAnswer(intent, slots, execution, requeryRounds);

        try (Tracer.Scope llm = tracer.span("llm.answer", SpanKind.LLM)) {
            llm.tag(AttrKeys.LLM_MODEL, "mock-agent-7b")
                    .tag(AttrKeys.LLM_STOP_REASON, "stop")
                    .tag("llm.prompt.chars", prompt.length());
            tracer.clock().sleep(140);
            long in = tokensOf(prompt.length());
            long out = tokensOf(answer.length());
            long cost = in * props.getLlmInPriceMicros() + out * props.getLlmOutPriceMicros();
            llm.tokens(in, out, cost).tag("llm.prompt.tokens", in);
            if (prompt.length() > 12_000) {
                ctx.noteCulprit(llm.spanId(), "答案 prompt 膨胀到 " + prompt.length() + " 字符");
                llm.integrity(false, "prompt 超出建议长度");
            }
        }

        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D19_ANSWER)) {
            d.record(Decision.at(DecisionPoint.D19_ANSWER)
                    .chosen("模板 + 证据引用(" + execution.outcomes().size() + " 条)")
                    .basis(Basis.MODEL)
                    .confidence(0.82)
                    .attr("prompt.chars", prompt.length())
                    .attr("requery.rounds", requeryRounds)
                    .alternative("只回状态码", 0.3, "省 token,用户看不懂")
                    .alternative("模板 + 证据引用", 0.82, "可控,可追溯")
                    .build());
        }
        trace.attr("answer.prompt.chars", prompt.length());
        return answer;
    }

    private String buildAnswer(Intent intent, Slots slots, Execution execution, int requeryRounds) {
        StepOutcome order = execution.ofTool("order.get");
        StepOutcome commit = execution.ofTool("refund.commit");
        StepOutcome doc = execution.ofTool("doc.search");
        StringBuilder sb = new StringBuilder();
        sb.append("已为你处理「").append(intent.label()).append("」。");
        if (order != null) {
            Object paid = order.data().get("paidCents");
            sb.append("订单 ").append(slots.string("orderId"));
            if (paid instanceof Number n) {
                sb.append(",实付 ").append(String.format("%.2f 元", n.intValue() / 100.0));
            }
            if (order.fallback()) {
                sb.append("(数据来自缓存,可能不是最新)");
            }
            sb.append("。");
        }
        if (commit != null) {
            Object refundId = commit.data().get("refundId");
            sb.append("退款单号 ").append(refundId == null ? "-" : refundId)
                    .append(",金额 ").append(String.format("%.2f 元",
                            (slots.integer("amountCents") == null ? 0 : slots.integer("amountCents")) / 100.0))
                    .append("。");
        }
        if (doc != null) {
            sb.append("依据知识库:").append(doc.summary());
            if (requeryRounds > 0) {
                sb.append("(补查 ").append(requeryRounds).append(" 轮)");
            }
            sb.append("。");
        }
        return sb.toString();
    }

    // ---- D20 结束 ----

    private Outcome decideStop(SelfCheck check, Execution execution) {
        Outcome outcome;
        if (!execution.allOk()) {
            outcome = Outcome.FAILED;
        } else if (!check.passed()) {
            outcome = Outcome.FAILED;
        } else {
            outcome = Outcome.SUCCESS;
        }
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D20_STOP)) {
            d.record(Decision.at(DecisionPoint.D20_STOP)
                    .chosen(switch (outcome) {
                        case SUCCESS -> "返回结果给用户";
                        case FAILED -> "返回失败并说明原因";
                        default -> "升级人工";
                    })
                    .basis(Basis.RULE)
                    .confidence(outcome == Outcome.SUCCESS ? 0.9 : 0.7)
                    .attr("outcome", outcome.name())
                    .attr("failures", check.failures())
                    .alternative("返回结果", outcome == Outcome.SUCCESS ? 0.9 : 0.2, "流程已收敛")
                    .alternative("升级人工", outcome == Outcome.SUCCESS ? 0.1 : 0.7, "失败且有副作用时更稳妥")
                    .build());
        }
        return outcome;
    }

    private long tokensOf(long chars) {
        return Math.max(1, Math.round(chars / 1.6));
    }
}
