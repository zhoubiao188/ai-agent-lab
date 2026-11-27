package com.example.o11y.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条决策记录。
 *
 * <p>最关键的字段是 {@link #alternatives}:被否掉的选项。没有它,台账就退化成又一份日志。
 * 有了它,你才能回答"为什么没走那条看起来更对的路"——而这恰恰是 Agent 事故复盘里
 * 唯一值钱的问题。</p>
 */
public final class Decision {

    /** 一个候选方案及其得分。 */
    public record Alternative(String name, double score, String reason) {
    }

    private final DecisionPoint point;
    private final int seq;
    private final String spanId;
    private final String chosen;
    private final Basis basis;
    private final double confidence;
    private final List<Alternative> alternatives;
    private final double latencyMs;
    private final Map<String, Object> attrs = new LinkedHashMap<>();

    private Decision(DecisionPoint point, int seq, String spanId, String chosen, Basis basis,
                     double confidence, List<Alternative> alternatives, double latencyMs,
                     Map<String, Object> attrs) {
        this.point = point;
        this.seq = seq;
        this.spanId = spanId;
        this.chosen = chosen;
        this.basis = basis;
        this.confidence = confidence;
        this.alternatives = new ArrayList<>(alternatives);
        this.latencyMs = latencyMs;
        // 构造器里必须显式拷贝 attrs。字段初始化器只是给了一个空 Map,
        // 忘了这一步的后果是"台账里所有决策的属性都丢了",而且编译期完全看不出来。
        this.attrs.putAll(attrs);
    }

    public static Builder at(DecisionPoint point) {
        return new Builder(point);
    }

    public DecisionPoint point() {
        return point;
    }

    public String code() {
        return point.code();
    }

    public int seq() {
        return seq;
    }

    public String spanId() {
        return spanId;
    }

    public String chosen() {
        return chosen;
    }

    public Basis basis() {
        return basis;
    }

    public double confidence() {
        return confidence;
    }

    public List<Alternative> alternatives() {
        return Collections.unmodifiableList(alternatives);
    }

    public double latencyMs() {
        return latencyMs;
    }

    public Map<String, Object> attrs() {
        return Collections.unmodifiableMap(attrs);
    }

    /**
     * 与最优被否方案的差距。差距越小,说明当时越接近"选错",越值得在页面上标黄。
     * 决策点没有候选(只有一个选项)时返回 1。
     */
    public double margin() {
        if (alternatives.isEmpty()) {
            return 1.0;
        }
        // 注意:候选里也包含"被选中"的那一项,算差距时必须先把它排除,
        // 否则 max(全部候选) 恒等于 chosen 自己,差距永远是 0,所有决策都会被标成"接近选错"。
        java.util.OptionalDouble chosenScore = alternatives.stream()
                .filter(a -> a.name().equals(chosen))
                .mapToDouble(Alternative::score)
                .findFirst();
        java.util.OptionalDouble bestRejected = alternatives.stream()
                .filter(a -> !a.name().equals(chosen))
                .mapToDouble(Alternative::score)
                .max();
        if (chosenScore.isEmpty() || bestRejected.isEmpty()) {
            return 1.0;
        }
        // 用相对差距而不是绝对差:不同决策点的分数量纲不同(有的是关键词分,有的是 0~1 的相似度),
        // 绝对差没法统一设阈值,相对差距可以统一用 0.1 当"接近选错"。
        double chosen = chosenScore.getAsDouble();
        if (chosen <= 1e-9) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (chosen - bestRejected.getAsDouble()) / chosen));
    }

    public Builder toBuilder() {
        Builder b = new Builder(point)
                .seq(seq)
                .spanId(spanId)
                .chosen(chosen)
                .basis(basis)
                .confidence(confidence)
                .latencyMs(latencyMs);
        alternatives.forEach(a -> b.alternative(a.name(), a.score(), a.reason()));
        attrs.forEach(b::attr);
        return b;
    }

    public static final class Builder {
        private final DecisionPoint point;
        private int seq;
        private String spanId;
        private String chosen;
        private Basis basis = Basis.RULE;
        private double confidence = 1.0;
        private final List<Alternative> alternatives = new ArrayList<>();
        private double latencyMs;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        private Builder(DecisionPoint point) {
            this.point = point;
        }

        public Builder seq(int seq) {
            this.seq = seq;
            return this;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder chosen(String chosen) {
            this.chosen = chosen;
            return this;
        }

        public Builder basis(Basis basis) {
            this.basis = basis;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = Math.max(0, Math.min(1, confidence));
            return this;
        }

        public Builder alternative(String name, double score, String reason) {
            this.alternatives.add(new Alternative(name, score, reason));
            return this;
        }

        public Builder latencyMs(double latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder attr(String key, Object value) {
            if (key != null && value != null) {
                this.attrs.put(key, value);
            }
            return this;
        }

        public Decision build() {
            return new Decision(point, seq, spanId, chosen, basis, confidence, alternatives, latencyMs, attrs);
        }
    }
}
