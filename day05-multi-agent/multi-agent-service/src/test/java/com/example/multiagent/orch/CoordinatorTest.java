package com.example.multiagent.orch;

import com.example.multiagent.agent.EventSink;
import com.example.multiagent.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinatorTest extends BaseTest {

    @Test
    @DisplayName("C0 无契约:计划错了照样全绿,系统宣称完成 —— 这就是假成功")
    void bareExecutionClaimsSuccess() {
        TaskResult result = coordinator.run(task("T03"), CoordPolicy.NONE, EventSink.NOOP);

        assertThat(result.claimed()).as("每一步都返回 200").isTrue();
        assertThat(result.success()).as("金标准:退款金额是编的").isFalse();
        assertThat(result.falseSuccess()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.startsWith("WRITE_UNGROUNDED"));
    }

    @Test
    @DisplayName("C1 静态校验:拦在写操作之前,一步都不执行")
    void validateBlocksBeforeAnyWrite() {
        TaskResult result = coordinator.run(task("T03"), CoordPolicy.VALIDATE, EventSink.NOOP);

        assertThat(result.executedSteps()).isZero();
        assertThat(result.reasons()).contains("VALIDATION_BLOCKED");
        assertThat(result.replans()).isZero();
    }

    @Test
    @DisplayName("C2 覆盖补步:漏掉的诉求被机械补回,不需要模型")
    void coveragePatchesMissingSlot() {
        TaskResult result = coordinator.run(task("T07"), CoordPolicy.COVERAGE, EventSink.NOOP);

        assertThat(result.success()).isTrue();
        assertThat(result.executedSteps()).isEqualTo(3);
    }

    @Test
    @DisplayName("C2 修不了顺序错:这类错只能重规划,补步补不回来")
    void coverageCannotFixBrokenOrder() {
        TaskResult result = coordinator.run(task("T03"), CoordPolicy.COVERAGE, EventSink.NOOP);

        assertThat(result.success()).isFalse();
        assertThat(result.replans()).isZero();
    }

    @Test
    @DisplayName("C3 拦下即重规划:写操作还没落地,所以还能救回来")
    void replanFixesBeforeWrite() {
        TaskResult result = coordinator.run(task("T03"), CoordPolicy.FULL, EventSink.NOOP);

        assertThat(result.replans()).isEqualTo(1);
        assertThat(result.success()).isTrue();
        assertThat(result.claimed()).isTrue();
    }

    @Test
    @DisplayName("C3 也能修好拆多:重规划把重复步骤去掉")
    void replanRemovesRedundancy() {
        TaskResult result = coordinator.run(task("T04"), CoordPolicy.FULL, EventSink.NOOP);

        assertThat(result.success()).isTrue();
        assertThat(result.executedSteps()).isEqualTo(3);
    }

    @Test
    @DisplayName("干净计划在 C3 下不触发任何修复")
    void cleanPlanNeedsNoRepair() {
        TaskResult result = coordinator.run(task("T05"), CoordPolicy.FULL, EventSink.NOOP);

        assertThat(result.success()).isTrue();
        assertThat(result.replans()).isZero();
        assertThat(result.critique()).isNotNull();
        assertThat(result.critique().verdict()).isEqualTo(com.example.multiagent.critic.Verdict.PASS);
    }

    @Test
    @DisplayName("漏项在 C1 下是假成功:计划跑完了,用户少拿到一个诉求")
    void underCoverIsFalseSuccessWithoutCoverageCheck() {
        TaskResult result = coordinator.run(task("T07"), CoordPolicy.VALIDATE, EventSink.NOOP);

        assertThat(result.claimed()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.startsWith("UNDER_COVER"));
    }
}
