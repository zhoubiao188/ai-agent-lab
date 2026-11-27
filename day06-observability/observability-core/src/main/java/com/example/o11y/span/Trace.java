package com.example.o11y.span;

import com.example.o11y.decision.Decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一次 Agent 运行的完整证据链:Span 树 + 决策台账 + 结论。
 *
 * <p>三者缺一不可。只有 Span 树,你看到"refund.commit 花了 300ms 且成功",但不知道为什么金额是 0;
 * 只有决策台账,你知道"参数来源选了默认值",但不知道这一步花了多久、有没有重试;
 * 只有结论,你只知道失败了。线上排障要的是三者能互相对上号,所以 Span 与 Decision 之间
 * 用 spanId 双向可见。</p>
 */
public final class Trace {

    private final String traceId;
    private final String taskId;
    private final String scenario;
    private final String question;
    private final String userId;
    private final long startedEpochMilli;

    private final Span root;
    private final List<Decision> decisions = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attrs = new LinkedHashMap<>();

    private Outcome outcome = Outcome.UNKNOWN;
    private boolean sampled = true;
    private String answer;
    /** 自检可信度。默认 1.0:没有显式打分的运行不应该因为"0 分"被采样规则误留。 */
    private double trust = 1.0;

    public Trace(String traceId, String taskId, String scenario, String question, String userId,
                 long startedEpochMilli, Span root) {
        this.traceId = traceId;
        this.taskId = taskId;
        this.scenario = scenario;
        this.question = question;
        this.userId = userId;
        this.startedEpochMilli = startedEpochMilli;
        this.root = root;
    }

    public String traceId() {
        return traceId;
    }

    public String taskId() {
        return taskId;
    }

    public String scenario() {
        return scenario;
    }

    public String question() {
        return question;
    }

    public String userId() {
        return userId;
    }

    public long startedEpochMilli() {
        return startedEpochMilli;
    }

    public Span root() {
        return root;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Trace outcome(Outcome outcome) {
        this.outcome = outcome;
        return this;
    }

    public boolean sampled() {
        return sampled;
    }

    public Trace sampled(boolean sampled) {
        this.sampled = sampled;
        return this;
    }

    public String answer() {
        return answer;
    }

    public Trace answer(String answer) {
        this.answer = answer;
        return this;
    }

    public double trust() {
        return trust;
    }

    public Trace trust(double trust) {
        this.trust = trust;
        return this;
    }

    public Trace attr(String key, Object value) {
        if (key != null && value != null) {
            attrs.put(key, value);
        }
        return this;
    }

    public Object attr(String key) {
        return attrs.get(key);
    }

    public Map<String, Object> attrs() {
        return Collections.unmodifiableMap(attrs);
    }

    public void addDecision(Decision decision) {
        decisions.add(decision);
    }

    public List<Decision> decisions() {
        return Collections.unmodifiableList(decisions);
    }

    public Optional<Decision> decision(String code) {
        return decisions.stream().filter(d -> d.code().equals(code)).findFirst();
    }

    /** 深度优先展开全部 Span(含根)。 */
    public List<Span> spans() {
        List<Span> out = new ArrayList<>(32);
        root.flatten(out);
        return out;
    }

    public Optional<Span> span(String spanId) {
        return spans().stream().filter(s -> s.spanId().equals(spanId)).findFirst();
    }

    public double totalMs() {
        return root.durationMs();
    }

    public long totalTokens() {
        return spans().stream().mapToLong(s -> s.tokensIn() + s.tokensOut()).sum();
    }

    public long totalCostMicros() {
        return spans().stream().mapToLong(Span::costMicros).sum();
    }

    public int errorCount() {
        return (int) spans().stream().filter(s -> s.status() == SpanStatus.ERROR).count();
    }

    /**
     * 重试放大系数 = 所有尝试次数 / 首次尝试次数。
     *
     * <p>每一次尝试都会生成一个独立的 TOOL Span(这样瀑布图上每次尝试都有独立的耗时和错误),
     * 所以不能拿"Span 个数"当分母 —— 那会把 3 次尝试算成 2.0。分母必须是 attempt==1 的 Span 数,
     * 也就是"真的有几次调用"。</p>
     */
    public double retryAmplification() {
        List<Span> attempts = spans().stream()
                .filter(s -> s.kind() == SpanKind.TOOL || s.kind() == SpanKind.WRITE)
                .toList();
        if (attempts.isEmpty()) {
            return 1.0;
        }
        long invocations = attempts.stream().filter(s -> s.attempts() <= 1).count();
        if (invocations == 0) {
            return 1.0;
        }
        return round(attempts.size() / (double) invocations);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    @Override
    public String toString() {
        return "Trace{" + traceId + " task=" + taskId + " outcome=" + outcome
                + " spans=" + spans().size() + " decisions=" + decisions.size()
                + " ms=" + String.format("%.0f", totalMs()) + "}";
    }
}
