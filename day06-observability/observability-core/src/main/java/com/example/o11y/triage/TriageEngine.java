package com.example.o11y.triage;

import com.example.o11y.attr.AttrKeys;
import com.example.o11y.decision.Decision;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 嫌疑排序:把一次运行里的几十个 Span 压成 3 个"最该看"的。
 *
 * <p>这件事为什么值得单独写一层:线上排障的真实瓶颈不是"看不到数据",而是"看到太多数据"。
 * 一次 Agent 运行 25~40 个 Span、20 条决策,失败时值班的人只有几分钟,
 * 必须有人先替他做一次初筛,并且这个初筛要能解释自己。</p>
 *
 * <p>六个信号,权重之和为 1:</p>
 * <p>1、selfShare 自身耗时占比:慢的通常就在关键路径上,但**只看慢会漏掉沉默失败**;</p>
 * <p>2、err 错误:自己错记 1,否则按 0.55 的子节点衰减继承 —— 让最深的那个错误胜出,
 *        而不是让根节点揽下所有责任;</p>
 * <p>3、retry 重试放大:同样的错被反复执行,代价是真实用户时间;</p>
 * <p>4、cost token 占比:钱烧在哪;</p>
 * <p>5、decisionAnomaly 决策异常:置信度低、和次优方案差距小于 0.1 的决策;</p>
 * <p>6、integrity 完整性:写入回显不一致、校验不通过这类**没有 error 状态**的坏结果,
 *        再按"已落地 / 护栏拦下"两档折算 —— 护栏报警说明成因在上游,不该霸占第一名。</p>
 */
public final class TriageEngine {

    /** 六个信号的权重。默认值是按 200 条故障注入样本调出来的,见 README 的调参记录。 */
    public record Weights(double self, double err, double retry, double cost,
                          double decision, double integrity) {

        public static final Weights DEFAULT = new Weights(0.20, 0.22, 0.10, 0.14, 0.16, 0.18);

        public double total() {
            return self + err + retry + cost + decision + integrity;
        }
    }

    /** 排障结论:前 N 个嫌疑 + 一句自动生成的"最可能原因"。 */
    public record Triage(List<Suspect> suspects, String narrative, int spanCount, int decisionCount) {
    }

    /**
     * token 归一化的参考预算。用"占本次运行的比例"会有一个系统性偏差:
     * 只要一次运行只有一次模型调用,那个 Span 的占比恒等于 100%,永远拿满分。
     * 改成"相对一个典型预算饱和",才能区分 500 token 的答案和 12000 token 的答案。
     */
    private static final double TOKEN_BUDGET = 8_000.0;

    private final Weights weights;

    public TriageEngine() {
        this(Weights.DEFAULT);
    }

    public TriageEngine(Weights weights) {
        this.weights = weights;
    }

    public Weights weights() {
        return weights;
    }

    public Triage triage(Trace trace, int limit) {
        List<Suspect> all = rank(trace);
        int n = Math.min(limit, all.size());
        List<Suspect> top = new ArrayList<>(all.subList(0, n));

        // 证据覆盖:分数排序只回答"谁最可疑",但排障清单还要回答"我凭什么能看见它"。
        // 完整性失败的那个 Span 往往是一次运行里唯一能直接肉眼核对的证据,
        // 一旦被几条同分的决策挤出前三,值班的人就只能靠"信算法",复盘会退化成猜。
        // 所以在这里补一条覆盖规则:清单里没有完整性证据时,把最后一名换成它。
        java.util.Set<String> integrityIds = new java.util.HashSet<>();
        for (Span s : trace.spans()) {
            if (!s.integrityOk()) {
                integrityIds.add(s.spanId());
            }
        }
        if (!integrityIds.isEmpty() && top.stream().noneMatch(s -> integrityIds.contains(s.spanId()))) {
            Suspect evidence = all.stream().filter(s -> integrityIds.contains(s.spanId()))
                    .findFirst().orElse(null);
            if (evidence != null) {
                if (top.size() >= limit && !top.isEmpty()) {
                    top.remove(top.size() - 1);
                }
                top.add(evidence);
            }
        }
        return new Triage(top, narrative(trace, top),
                trace.spans().size(), trace.decisions().size());
    }

    public List<Suspect> rank(Trace trace) {
        Span root = trace.root();
        List<Span> spans = trace.spans();
        // 分母必须是整次运行的时长。用根的 selfTime 会得到 0(子 Span 把根的时间全占了),
        // 除以 0 之后每个 Span 的 selfShare 都被压成 1.0 —— 所有 Span 同分,排序直接失效。
        double runMs = Math.max(trace.totalMs(), 1e-6);
        long totalTokens = Math.max(1, trace.totalTokens());

        Map<String, Double> errScores = new LinkedHashMap<>();
        for (Span s : spans) {
            errScores.put(s.spanId(), 0.0);
        }
        computeErr(root, errScores);

        List<Suspect> suspects = new ArrayList<>(spans.size());
        for (Span span : spans) {
            if (span.kind() == SpanKind.RUN) {
                continue;
            }
            double selfShare = clamp(span.selfMs() / runMs);
            double err = clamp(errScores.getOrDefault(span.spanId(), 0.0));
            double retry = span.attempts() > 1 ? clamp((span.attempts() - 1) / 2.0) : 0.0;
            double cost = clamp((span.tokensIn() + span.tokensOut()) / TOKEN_BUDGET);
            double decision = span.kind() == SpanKind.DECISION ? decisionAnomaly(trace, span) : 0.0;
            // 完整性信号按"落地 / 拦下"分档。这一档是 ARG_DRIFT 场景的成败分界:
            // 试算接口拒绝执行属于护栏报警,方向在上游决策;如果它和被写坏的 Span 同分,
            // 那些"坏东西被生成出来"的现场永远排在"坏东西被发现"的位置后面。
            double integrity = span.integrityOk() ? 0.0 : span.integrityGrade().severity();

            double score = (weights.self() * selfShare
                    + weights.err() * err
                    + weights.retry() * retry
                    + weights.cost() * cost
                    + weights.decision() * decision
                    + weights.integrity() * integrity) / weights.total();

            if (score <= 0.0001) {
                continue;
            }
            Map<String, Double> contributions = new LinkedHashMap<>();
            contributions.put("selfMs", round3(weights.self() * selfShare / weights.total()));
            contributions.put("error", round3(weights.err() * err / weights.total()));
            contributions.put("retry", round3(weights.retry() * retry / weights.total()));
            contributions.put("tokens", round3(weights.cost() * cost / weights.total()));
            contributions.put("decision", round3(weights.decision() * decision / weights.total()));
            contributions.put("integrity", round3(weights.integrity() * integrity / weights.total()));

            suspects.add(new Suspect(span.spanId(), span.name(), span.kind(), span.decisionCode(),
                    round3(score), contributions,
                    evidence(trace, span, runMs, totalTokens, err, selfShare)));
        }
        suspects.sort(Comparator.comparingDouble(Suspect::score).reversed());
        return suspects;
    }

    /**
     * 错误分自底向上衰减:自身 ERROR 记 1,父节点拿子节点最大值的 0.55。
     * 衰减系数大于 0.5 是为了让"两步之外"的父节点仍能进候选,小于 1 是为了保证
     * 越深的节点分越高 —— 真正的根因在叶子,不在根。
     */
    private void computeErr(Span span, Map<String, Double> out) {
        double maxChild = 0;
        for (Span child : span.children()) {
            computeErr(child, out);
            maxChild = Math.max(maxChild, out.getOrDefault(child.spanId(), 0.0));
        }
        double own = span.status() == SpanStatus.ERROR ? 1.0 : 0.0;
        out.put(span.spanId(), Math.max(own, 0.55 * maxChild));
    }

    /** 决策异常度:置信度越低越可疑;与次优方案的差距小于 0.1 时再加一档。 */
    private double decisionAnomaly(Trace trace, Span span) {
        double confidence = span.attr(AttrKeys.DECISION_CONFIDENCE) instanceof Number n
                ? n.doubleValue() : 1.0;
        double margin = span.attr(AttrKeys.DECISION_MARGIN) instanceof Number m
                ? m.doubleValue() : 1.0;
        double anomaly = 1.0 - clamp(confidence);
        if (margin <= 0.0001) {
            // 选中的方案并不比被否掉的更好 —— 这不是"接近",这是"选错了",权重应该更高
            anomaly = Math.min(1.0, anomaly + 0.40);
        } else if (margin < 0.1) {
            anomaly = Math.min(1.0, anomaly + 0.15);
        }
        // 低置信度决策 + 本次运行存在语义失败 → 这个决策几乎是"因";
        // 而失败的那个 Span 只是"果"。加一档,让根因别被症状挤掉。
        boolean semanticFailure = trace.spans().stream().anyMatch(s -> !s.integrityOk());
        if (semanticFailure && confidence < 0.6) {
            anomaly = Math.min(1.0, anomaly + 0.15);
        }
        double weight = span.decisionCode() == null ? 1.0
                : com.example.o11y.decision.DecisionPoint.of(span.decisionCode()).weight();
        return Math.min(1.0, anomaly * weight);
    }

    private List<String> evidence(Trace trace, Span span, double runMs, long totalTokens,
                                  double err, double selfShare) {
        List<String> out = new ArrayList<>(4);
        if (selfShare >= 0.15) {
            out.add(String.format("自身耗时 %.0fms,占本次运行 %.0f%%", span.selfMs(), selfShare * 100));
        }
        if (span.status() == SpanStatus.ERROR) {
            out.add("状态 ERROR:" + (span.errorKind() == null ? "未分类" : span.errorKind())
                    + (span.errorMessage() == null ? "" : "(" + span.errorMessage() + ")"));
        } else if (err > 0.2) {
            out.add(String.format("子链路存在错误,衰减继承分 %.2f", err));
        }
        if (span.attempts() > 1) {
            out.add("实际尝试 " + span.attempts() + " 次,耗时被放大");
        }
        long tokens = span.tokensIn() + span.tokensOut();
        if (tokens > 0 && tokens / (double) totalTokens >= 0.25 && tokens >= 800) {
            out.add(String.format("消耗 %d token,占本次运行 %.0f%%", tokens, tokens * 100.0 / totalTokens));
        }
        if (!span.integrityOk()) {
            out.add(span.integrityGrade().label() + ":" + span.attrString("integrity.reason"));
            if (span.integrityGrade() == com.example.o11y.span.IntegrityGrade.CAUGHT) {
                out.add("这里是护栏,不是现场:坏结果没落地,成因在上游决策");
            }
        }
        if (span.kind() == SpanKind.DECISION) {
            Optional<Decision> d = trace.decision(span.decisionCode());
            d.ifPresent(dec -> {
                out.add(String.format("决策依据 %s,置信度 %.2f,与次优方案差距 %.2f",
                        dec.basis().label(), dec.confidence(), dec.margin()));
                if (!dec.alternatives().isEmpty()) {
                    out.add("被否掉的选项:" + dec.alternatives().stream()
                            .map(Decision.Alternative::name).limit(3).toList());
                }
            });
        }
        if (out.isEmpty()) {
            out.add("信号弱,建议结合相邻 Span 一起看");
        }
        return out;
    }

    /** 自动写一段复盘开头。它不替代人,但能把"从零开始读"变成"从结论开始核对"。 */
    private String narrative(Trace trace, List<Suspect> top) {
        if (top.isEmpty()) {
            return "本次运行没有明显异常信号。";
        }
        Suspect first = top.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("结论:").append(trace.outcome().label()).append("。最可能的问题在 ")
                .append(first.name()).append("(").append(first.kind().label()).append(")");
        if (!first.evidence().isEmpty()) {
            sb.append(",依据:").append(String.join(";", first.evidence()));
        }
        sb.append("。");
        if (top.size() > 1) {
            sb.append("备选嫌疑:")
                    .append(String.join("、", top.subList(1, top.size()).stream().map(Suspect::name).toList()))
                    .append("。");
        }
        boolean silent = trace.errorCount() == 0
                && trace.spans().stream().anyMatch(s -> !s.integrityOk());
        if (silent) {
            sb.append("注意:本次没有 ERROR 状态的 Span,靠完整性信号才被发现。");
        }
        return sb.toString();
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
