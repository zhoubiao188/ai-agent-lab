package com.example.rag.eval;

import com.example.rag.chunk.Chunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    private Chunk chunk(String id, String docId, String text) {
        return new Chunk(id, docId, "文档", "小节", 0, text);
    }

    /** 正确块在第 1 名。 */
    private final List<Chunk> hits1 = List.of(
            chunk("a1", "docA", "正确内容 锚点甲"),
            chunk("b1", "docB", "无关内容"),
            chunk("a2", "docA", "同文档的另一块"));

    /** 正确块在第 3 名。 */
    private final List<Chunk> hits3 = List.of(
            chunk("b1", "docB", "无关内容"),
            chunk("c1", "docC", "无关内容"),
            chunk("a1", "docA", "正确内容 锚点甲"));

    private final List<Gold> golds = List.of(new Gold("docA", "锚点甲"));

    @Test
    @DisplayName("recall@k 与名次的关系")
    void recallAtK() {
        assertThat(Metrics.recallAtK(hits1, golds, 1)).isEqualTo(1.0);
        assertThat(Metrics.recallAtK(hits3, golds, 2)).isZero();
        assertThat(Metrics.recallAtK(hits3, golds, 3)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("MRR 等于首个正确结果名次的倒数")
    void mrr() {
        assertThat(Metrics.mrr(hits1, golds)).isEqualTo(1.0);
        assertThat(Metrics.mrr(hits3, golds)).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(Metrics.mrr(List.of(chunk("x", "docX", "无关")), golds)).isZero();
    }

    @Test
    @DisplayName("NDCG@5 手算校验:第 1 名命中为 1,第 3 名命中为 0.5")
    void ndcgHandComputed() {
        assertThat(Metrics.ndcgAtK(hits1, golds, 5)).isEqualTo(1.0);
        // DCG = 1/log2(4) = 0.5,IDCG = 1/log2(2) = 1
        assertThat(Metrics.ndcgAtK(hits3, golds, 5)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("同一 gold 被两条结果命中时不能重复计增益,NDCG 不得超过 1")
    void ndcgNeverExceedsOne() {
        List<Chunk> duplicated = List.of(
                chunk("a1", "docA", "正确内容 锚点甲"),
                chunk("a2", "docA", "正确内容 锚点甲 重复"));
        assertThat(Metrics.ndcgAtK(duplicated, golds, 5)).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("锚点不匹配时算未命中 —— 锚点是用来区分同文档不同块的")
    void anchorMatters() {
        List<Gold> other = List.of(new Gold("docA", "锚点乙"));
        assertThat(Metrics.recallAtK(hits1, other, 5)).isZero();
    }

    @Test
    @DisplayName("多 gold 时 recall 按比例计算")
    void multiGold() {
        List<Gold> two = List.of(new Gold("docA", "锚点甲"), new Gold("docB", "无关"));
        assertThat(Metrics.recallAtK(hits1, two, 5)).isEqualTo(1.0);
        assertThat(Metrics.recallAtK(hits1, two, 1)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("百分位延迟取值不越界")
    void percentile() {
        List<Long> v = List.of(10L, 20L, 30L, 40L, 50L);
        assertThat(Metrics.percentile(v, 50)).isEqualTo(30L);
        assertThat(Metrics.percentile(v, 95)).isEqualTo(50L);
        assertThat(Metrics.percentile(List.of(), 95)).isZero();
    }
}
