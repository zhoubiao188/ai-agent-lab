package com.example.multiagent.coverage;

import com.example.multiagent.plan.Plan;
import com.example.multiagent.support.BaseTest;
import com.example.multiagent.tasks.TaskSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentCoverageTest extends BaseTest {

    @Test
    @DisplayName("完整计划:覆盖率 100%")
    void complete() {
        TaskSpec task = task("T01");
        CoverageReport report = coverage.check(clean(task), task);

        assertThat(report.complete()).isTrue();
        assertThat(report.ratio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("少一个诉求:计划本身完全合法,只有对账能发现")
    void missingSlot() {
        TaskSpec task = task("T01");
        Plan plan = clean(task).remove("s5");

        CoverageReport report = coverage.check(plan, task);

        assertThat(report.complete()).isFalse();
        assertThat(report.missing()).containsExactly("ticket");
        assertThat(validator.validate(plan)).as("结构上完全合法").isEmpty();
    }

    @Test
    @DisplayName("机械补步:补完就齐了,而且补出来的步骤参数接得上上游")
    void patchRestoresCoverage() {
        TaskSpec task = task("T01");
        Plan plan = clean(task).remove("s5");

        Plan patched = coverage.patch(plan, task, coverage.check(plan, task));

        assertThat(coverage.check(patched, task).complete()).isTrue();
        assertThat(validator.validate(patched)).isEmpty();
        assertThat(patched.find("s5").orElseThrow().dependsOn()).contains("s1");
    }

    @Test
    @DisplayName("补步不需要模型:同一份输入补两次,结果完全一样")
    void patchIsDeterministic() {
        TaskSpec task = task("T01");
        Plan plan = clean(task).remove("s5");

        Plan a = coverage.patch(plan, task, coverage.check(plan, task));
        Plan b = coverage.patch(plan, task, coverage.check(plan, task));

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    @DisplayName("覆盖率是连续量,不是布尔量:3 个诉求做了 2 个就是 0.667")
    void partialRatio() {
        TaskSpec task = task("T09");
        Plan plan = clean(task).remove("s3");

        CoverageReport report = coverage.check(plan, task);

        assertThat(report.ratio()).isEqualTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.001));
    }
}
