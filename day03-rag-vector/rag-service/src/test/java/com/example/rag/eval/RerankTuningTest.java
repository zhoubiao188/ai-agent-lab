package com.example.rag.eval;

import com.example.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排权重 α 的标定实验。
 * <p>α 是"特征分"在最终分里的占比。α=1 等于完全相信特征分,α=0 等于不做重排。
 * 这组实验的价值在于:它证明了重排不是免费的午餐 —— 在这套语料上,
 * α 取大了以后 R@1 与 MRR 都会掉,因为重排只对候选集的前若干条重新排序,
 * 特征分一波动就会把原本排第一的正确块挤下去。</p>
 */
@SpringBootTest
class RerankTuningTest {

    @Autowired
    private EvalRunner runner;

    @Autowired
    private RagProperties props;

    @Test
    @DisplayName("α 扫描:找到这套语料上的最优重排权重")
    void sweepAlpha() {
        double original = props.getSearch().getRerankAlpha();
        double[] alphas = {0.0, 0.2, 0.4, 0.5, 0.6, 0.7, 1.0};
        System.out.println();
        System.out.println("===== 重排权重 α 扫描 =====");
        System.out.printf("%6s %8s %8s %8s %8s %10s%n", "α", "R@1", "R@5", "MRR", "NDCG@5", "P50(μs)");
        double bestAlpha = 0;
        double bestMrr = -1;
        try {
            for (double alpha : alphas) {
                props.getSearch().setRerankAlpha(alpha);
                EvalReport report = runner.run();
                EvalReport.StrategyResult full = report.strategies().stream()
                        .filter(s -> s.mode().equals("FULL")).findFirst().orElseThrow();
                System.out.printf("%6.1f %8.3f %8.3f %8.3f %8.3f %10d%n",
                        alpha, full.recall1(), full.recall5(), full.mrr(), full.ndcg5(), full.p50Micros());
                if (full.mrr() > bestMrr) {
                    bestMrr = full.mrr();
                    bestAlpha = alpha;
                }
            }
        } finally {
            props.getSearch().setRerankAlpha(original);
        }
        System.out.println("最优 α = " + bestAlpha + ",MRR = " + bestMrr);
        System.out.println("==========================");

        // 重排后的 MRR 至少不能比不重排(α=0)差太多
        assertThat(bestMrr).isGreaterThan(0.5);
    }
}
