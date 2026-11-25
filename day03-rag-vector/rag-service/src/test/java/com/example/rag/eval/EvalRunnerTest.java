package com.example.rag.eval;

import com.example.rag.chunk.Chunk;
import com.example.rag.rag.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测集自检 + 全量消融实验。
 * <p>自检那一条是被逼出来的:一开始有 6 条样本的锚点写错了(锚点是小节标题,
 * 而块里只有正文),导致这几个 gold 永远不可能命中,还以为是检索算法的问题。
 * 现在锚点不存在就直接让测试失败。</p>
 */
@SpringBootTest
class EvalRunnerTest {

    @Autowired
    private EvalRunner runner;

    @Autowired
    private Corpus corpus;

    @Test
    @DisplayName("评测集自检:每条样本的锚点必须真实存在于对应文档的某个块里")
    void everyAnchorExists() {
        List<EvalCase> cases = runner.loadCases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);

        for (EvalCase c : cases) {
            for (Gold g : c.golds()) {
                boolean found = corpus.chunks().stream()
                        .filter(chunk -> chunk.docId().equals(g.docId()))
                        .anyMatch(chunk -> chunk.text().contains(g.anchor()));
                assertThat(found)
                        .as("样本 %s 的锚点 [%s] 在文档 %s 中不存在", c.id(), g.anchor(), g.docId())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("全量消融实验:四种策略的指标对比")
    void ablation() {
        EvalReport report = runner.run();
        assertThat(report.cases()).isEqualTo(runner.loadCases().size());
        assertThat(report.strategies()).hasSize(4);

        System.out.println();
        System.out.println("===== RAG 召回消融实验 =====");
        System.out.printf("语料:%d 篇 / %d 块,评测集:%d 条,向量模型:%s,索引:%s%n",
                report.docs(), report.chunks(), report.cases(), report.embeddingModel(), report.indexType());
        System.out.printf("%-16s %8s %8s %8s %8s %8s %10s%n",
                "策略", "R@1", "R@3", "R@5", "MRR", "NDCG@5", "P95(μs)");
        for (EvalReport.StrategyResult s : report.strategies()) {
            System.out.printf("%-16s %8.3f %8.3f %8.3f %8.3f %8.3f %10d%n",
                    s.label(), s.recall1(), s.recall3(), s.recall5(), s.mrr(), s.ndcg5(), s.p95Micros());
        }
        System.out.println("--- 按查询类型拆解(R@5) ---");
        for (EvalReport.StrategyResult s : report.strategies()) {
            System.out.printf("%-16s", s.label());
            s.byType().forEach((type, stat) -> System.out.printf(" %s=%.2f", type, stat.recall5()));
            System.out.println();
        }
        System.out.println("--- 策略增量 ---");
        report.deltas().forEach(d -> System.out.printf("%-12s→%-12s R@5 %+.3f  MRR %+.3f  NDCG@5 %+.3f%n",
                d.from(), d.to(), d.recall5(), d.mrr(), d.ndcg5()));
        System.out.println("======================");

        EvalReport.StrategyResult bm25 = byMode(report, "BM25");
        EvalReport.StrategyResult vector = byMode(report, "VECTOR");
        EvalReport.StrategyResult hybrid = byMode(report, "HYBRID");
        EvalReport.StrategyResult full = byMode(report, "FULL");

        // 融合策略不应该比任一单路明显更差,这是 RRF 的基本承诺
        assertThat(hybrid.recall3()).isGreaterThanOrEqualTo(Math.min(bm25.recall3(), vector.recall3()));
        // 两组单路策略在同一批样本上必须有差异,否则说明其中一路没生效
        assertThat(bm25.recall5()).isNotEqualTo(vector.recall5());
        // 标定过 α 之后,重排的 MRR 不应低于融合(α 取大了会掉,见 RerankTuningTest)
        assertThat(full.mrr()).isGreaterThanOrEqualTo(hybrid.mrr() - 0.02);
        // 多跳类查询是混合检索的核心收益点:两路单召都容易漏掉其中一个文档
        assertThat(hybrid.byType().get("multi-hop").recall5())
                .isGreaterThanOrEqualTo(Math.min(bm25.byType().get("multi-hop").recall5(),
                        vector.byType().get("multi-hop").recall5()));
    }

    @Test
    @DisplayName("评测结果可重复:同一批样本跑两次指标一致")
    void deterministic() {
        EvalReport a = runner.run();
        EvalReport b = runner.run();
        for (int i = 0; i < a.strategies().size(); i++) {
            assertThat(a.strategies().get(i).mrr()).isEqualTo(b.strategies().get(i).mrr());
        }
    }

    @Test
    @DisplayName("每条明细都记录了四种模式下的命中名次")
    void detailsCoverAllModes() {
        EvalReport report = runner.run();
        assertThat(report.details()).isNotEmpty();
        EvalReport.CaseRow row = report.details().get(0);
        assertThat(row.ranks()).containsKeys("BM25", "VECTOR", "HYBRID", "FULL");
        assertThat(row.golds()).isNotEmpty();
    }

    private EvalReport.StrategyResult byMode(EvalReport report, String mode) {
        return report.strategies().stream()
                .filter(s -> s.mode().equals(mode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少策略 " + mode));
    }

    @Test
    @DisplayName("语料库规模合理,块长不超上限")
    void corpusSane() {
        assertThat(corpus.chunks()).hasSizeGreaterThan(30);
        assertThat(corpus.docs()).hasSizeGreaterThanOrEqualTo(6);
        for (Chunk c : corpus.chunks()) {
            assertThat(c.text().length()).isLessThanOrEqualTo(700);
        }
    }
}
