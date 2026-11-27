package com.example.agent.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表 + 候选召回。
 *
 * <p>召回打分刻意做得粗糙(关键词命中率 + 意图亲和度),因为本文要观察的不是"召回算法有多强",
 * 而是"召回错了能不能被看见"。D03 会把每个候选的得分写进台账,
 * 正确的工具没进候选集时,你至少能一眼看出它差了多少分。</p>
 */
public final class ToolRegistry {

    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

    public ToolRegistry register(ToolSpec spec) {
        specs.put(spec.name(), spec);
        return this;
    }

    public Optional<ToolSpec> find(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    public ToolSpec require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
    }

    public List<ToolSpec> all() {
        return new ArrayList<>(specs.values());
    }

    public int size() {
        return specs.size();
    }

    /** 一个候选及其得分,写进 D03 台账。 */
    public record Candidate(String tool, double score, int hits) {
    }

    /**
     * 按问题文本召回 topK 候选。
     *
     * <p>得分 = 关键词命中数 / sqrt(关键词总数),后面再乘以意图亲和度。
     * 除以 sqrt 是为了不让"关键词写得多的工具"天然占便宜。</p>
     */
    public List<Candidate> recall(String question, String intent, Map<String, Double> affinity, int topK) {
        String q = question == null ? "" : question.toLowerCase();
        List<Candidate> out = new ArrayList<>();
        for (ToolSpec spec : specs.values()) {
            int hits = 0;
            for (String kw : spec.keywords()) {
                if (q.contains(kw.toLowerCase())) {
                    hits++;
                }
            }
            double base = hits == 0 ? 0 : hits / Math.sqrt(spec.keywords().size());
            double bonus = affinity.getOrDefault(spec.name(), 0.0);
            double score = base + bonus;
            if (score > 0) {
                out.add(new Candidate(spec.name(), round3(score), hits));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out.subList(0, Math.min(topK, out.size()));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
