package com.example.agent.pipeline;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 规则意图解析。没用模型,因为这一步用规则就够了 —— 也正因为用规则,
 * D01 的置信度才是可计算的(top 与次优的差距),而不是模型给的一个玄学数字。
 */
public final class IntentParser {

    public record IntentResult(Intent intent, double confidence, Map<String, Integer> scores) {

        public int top() {
            return scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        public int second() {
            return scores.values().stream().mapToInt(Integer::intValue)
                    .filter(v -> v < top()).max().orElse(0);
        }

        public Optional<String> runnerUp() {
            int top = top();
            return scores.entrySet().stream()
                    .filter(e -> e.getValue() < top)
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey);
        }
    }

    public IntentResult parse(String question) {
        String q = question == null ? "" : question;
        Map<String, Integer> scores = new LinkedHashMap<>();
        Intent best = Intent.DOC;
        int bestScore = -1;

        for (Intent intent : Intent.values()) {
            int score = 0;
            for (Map.Entry<String, Integer> signal : intent.signals().entrySet()) {
                if (q.contains(signal.getKey())) {
                    score += signal.getValue();
                }
            }
            scores.put(intent.name(), score);
            if (score > bestScore) {
                bestScore = score;
                best = intent;
            }
        }

        if (bestScore <= 0) {
            scores.put("NONE", 0);
            return new IntentResult(Intent.DOC, 0.3, scores);
        }

        IntentResult tmp = new IntentResult(best, 0, scores);
        int top = tmp.top();
        int second = tmp.second();
        double confidence = 0.5 + 0.45 * (top - second) / (double) (top + second);
        return new IntentResult(best, Math.round(confidence * 100) / 100.0, scores);
    }
}
