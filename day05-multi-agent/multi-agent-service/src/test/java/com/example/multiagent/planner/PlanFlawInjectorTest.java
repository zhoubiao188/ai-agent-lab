package com.example.multiagent.planner;

import com.example.multiagent.coverage.CoverageReport;
import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.PlanFlaw;
import com.example.multiagent.plan.PlanViolation;
import com.example.multiagent.plan.ViolationCode;
import com.example.multiagent.support.BaseTest;
import com.example.multiagent.tasks.TaskSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanFlawInjectorTest extends BaseTest {

    @Test
    @DisplayName("不注入缺陷时,计划原样返回")
    void noFlawKeepsPlan() {
        Plan plan = clean(task("T01"));

        assertThat(flawInjector.inject(plan, null).fingerprint()).isEqualTo(plan.fingerprint());
    }

    @Test
    @DisplayName("拆漏:删掉一个尾部步骤,结构依然合法")
    void underCover() {
        TaskSpec task = task("T01");
        Plan plan = flawInjector.inject(clean(task), PlanFlaw.UNDER_COVER);

        assertThat(plan.size()).isEqualTo(4);
        CoverageReport report = coverage.check(plan, task);
        assertThat(report.complete()).isFalse();
        assertThat(validator.validate(plan)).as("只有对账能发现").isEmpty();
    }

    @Test
    @DisplayName("拆错:退款金额变成字面量,校验能抓到")
    void wrongOrder() {
        TaskSpec task = task("T03");
        Plan plan = flawInjector.inject(clean(task), PlanFlaw.WRONG_ORDER);

        List<ViolationCode> codes = validator.validate(plan).stream().map(PlanViolation::code).toList();
        assertThat(codes).contains(ViolationCode.UNGROUNDED_WRITE);
        assertThat(plan.find("s3").orElseThrow().args().get("amount").isNumber()).isTrue();
    }

    @Test
    @DisplayName("拆多:多一个重复步骤,校验能抓到")
    void overSplit() {
        TaskSpec task = task("T01");
        Plan plan = flawInjector.inject(clean(task), PlanFlaw.OVER_SPLIT);

        assertThat(plan.size()).isEqualTo(6);
        assertThat(validator.validate(plan).stream().map(PlanViolation::code).toList())
                .contains(ViolationCode.REDUNDANT_STEP);
        assertThat(coverage.check(plan, task).complete()).as("拆多不影响覆盖").isTrue();
    }

    @Test
    @DisplayName("注入是确定性的:同一份计划注入两次结果一致")
    void deterministic() {
        Plan plan = clean(task("T01"));

        assertThat(flawInjector.inject(plan, PlanFlaw.OVER_SPLIT).fingerprint())
                .isEqualTo(flawInjector.inject(plan, PlanFlaw.OVER_SPLIT).fingerprint());
    }
}
