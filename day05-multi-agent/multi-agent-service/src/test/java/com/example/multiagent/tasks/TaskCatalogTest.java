package com.example.multiagent.tasks;

import com.example.multiagent.bench.BenchReport;
import com.example.multiagent.orch.CoordPolicy;
import com.example.multiagent.plan.PlanFlaw;
import com.example.multiagent.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCatalogTest extends BaseTest {

    @Test
    @DisplayName("20 个任务,缺陷分布 6 干净 + 5 拆漏 + 5 拆错 + 4 拆多")
    void distribution() {
        List<TaskSpec> all = catalog.all();

        assertThat(all).hasSize(20);
        assertThat(all).filteredOn(t -> !t.hasFlaw()).hasSize(6);
        assertThat(all).filteredOn(t -> t.forcedFlaw() == PlanFlaw.UNDER_COVER).hasSize(5);
        assertThat(all).filteredOn(t -> t.forcedFlaw() == PlanFlaw.WRONG_ORDER).hasSize(5);
        assertThat(all).filteredOn(t -> t.forcedFlaw() == PlanFlaw.OVER_SPLIT).hasSize(4);
    }

    @Test
    @DisplayName("拆错只能注入到含写操作的任务上,否则缺陷注入会静默失效")
    void wrongOrderNeedsWritableTask() {
        assertThat(catalog.all())
                .filteredOn(t -> t.forcedFlaw() == PlanFlaw.WRONG_ORDER)
                .allMatch(t -> t.slots().contains("refund"));
    }

    @Test
    @DisplayName("四组配置必须真的只有防线层数不同")
    void policiesDifferOnlyInDefenses() {
        assertThat(CoordPolicy.NONE.validate()).isFalse();
        assertThat(CoordPolicy.VALIDATE.coverage()).isFalse();
        assertThat(CoordPolicy.COVERAGE.critic()).isFalse();
        assertThat(CoordPolicy.FULL.critic()).isTrue();
    }

    @Test
    @DisplayName("小样本对照:C3 的成功率不低于 C0,且 C1 会因为'只拦不修'而下降")
    void benchShowsTrend() {
        List<TaskSpec> subset = List.of(task("T01"), task("T03"), task("T04"), task("T07"));

        BenchReport report = bench.run(List.of(CoordPolicy.NONE, CoordPolicy.VALIDATE,
                CoordPolicy.COVERAGE, CoordPolicy.FULL), subset);

        assertThat(report.rows()).hasSize(4);
        BenchReport.Row c0 = report.rows().get(0);
        BenchReport.Row c1 = report.rows().get(1);
        BenchReport.Row c3 = report.rows().get(3);

        assertThat(c0.falseSuccess()).isGreaterThan(0);
        assertThat(c1.blocked()).isGreaterThan(0);
        assertThat(c3.success()).isGreaterThan(c0.success());
        assertThat(c3.success()).isEqualTo(4);
    }
}
