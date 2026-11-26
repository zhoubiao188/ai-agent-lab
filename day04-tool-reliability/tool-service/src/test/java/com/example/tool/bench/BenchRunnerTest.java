package com.example.tool.bench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠性阶梯实验。跑的是真链路,不是 mock 掉的假数据 —— 这也是它比别人慢的原因。
 * 表里的数字就是文章里那张表的来源。
 */
@SpringBootTest
class BenchRunnerTest {

    @Autowired
    private BenchRunner runner;

    @Test
    void 四组配置的成功率应当单调上升() {
        BenchReport report = runner.run(120, 20261126L, 0.1);
        print(report);

        assertThat(report.rows()).hasSize(4);
        BenchReport.Row baseline = report.rows().get(0);
        BenchReport.Row retry = report.rows().get(1);
        BenchReport.Row repair = report.rows().get(2);
        BenchReport.Row full = report.rows().get(3);

        assertThat(retry.successRate()).isGreaterThan(baseline.successRate());
        assertThat(repair.successRate()).isGreaterThan(retry.successRate());
        assertThat(full.successRate()).isGreaterThanOrEqualTo(repair.successRate());
        // 并行只改善延迟,不改善成功率:成功任务的均值应该明显下降
        assertThat(full.avgMs()).isLessThan(repair.avgMs());
    }

    static void print(BenchReport report) {
        System.out.println("\n任务数=" + report.tasks() + " seed=" + report.seed()
                + " 延迟倍率=" + report.latencyScale() + " 总耗时=" + report.totalMs() + "ms");
        System.out.printf("%-18s %5s %5s %5s %8s %8s %8s %8s %6s %6s %6s%n",
                "配置", "成功", "降级", "失败", "成功率", "均值ms", "P95ms", "调用数", "重试", "修复", "降级数");
        for (BenchReport.Row row : report.rows()) {
            System.out.printf("%-18s %5d %5d %5d %7.1f%% %8d %8d %8d %6d %6d %6d%n",
                    row.policy(), row.success(), row.degraded(), row.failed(),
                    row.successRate() * 100, row.avgMs(), row.p95Ms(), row.calls(),
                    row.retries(), row.repairs(), row.fallbacks());
            if (!row.failureKinds().isEmpty()) {
                System.out.println("    失败原因: " + row.failureKinds());
            }
        }
    }
}
