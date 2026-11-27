package com.example.multiagent.critic;

import com.example.multiagent.orch.StepOutcome;
import com.example.multiagent.plan.Plan;
import com.example.multiagent.support.BaseTest;
import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CriticTest extends BaseTest {

    private TaskSpec t() {
        return task("T03");
    }

    @Test
    @DisplayName("承诺的产出全部兑现、写操作已溯源 → PASS")
    void pass() {
        Critique critique = critic.review(clean(t()), t(), healthy());

        assertThat(critique.verdict()).isEqualTo(Verdict.PASS);
        assertThat(critique.findings()).isEmpty();
    }

    @Test
    @DisplayName("少了一个诉求 → PATCH:这种错能机械修,不值得重规划")
    void patch() {
        Plan plan = clean(t()).remove("s3");
        List<StepOutcome> outcomes = healthy().stream().filter(o -> !o.stepId().equals("s3")).toList();

        Critique critique = critic.review(plan, t(), outcomes);

        assertThat(critique.verdict()).isEqualTo(Verdict.PATCH);
        assertThat(critique.uncoveredSlots()).containsExactly("refund");
    }

    @Test
    @DisplayName("退款参数没溯源 → REPLAN:计划本身就是错的,补一步救不回来")
    void replan() {
        Plan plan = clean(t());
        List<StepOutcome> outcomes = List.of(
                output("s1", "order.query", 199.0, 0.0, true),
                output("s2", "refund.quote", 199.0, 0.0, false),
                new StepOutcome("s3", "refund.submit", StepOutcome.Status.OK, 5, null,
                        refundPayload(199.0), List.of()));

        Critique critique = critic.review(plan, t(), outcomes);

        assertThat(critique.verdict()).isEqualTo(Verdict.REPLAN);
        assertThat(critique.ungroundedWrites()).isNotEmpty();
    }

    @Test
    @DisplayName("声明的产出没给出来 → REPLAN:模型在 produces 里写了漂亮字段,实际只返回一个 id")
    void missingOutput() {
        Plan plan = clean(t());
        List<StepOutcome> outcomes = List.of(
                new StepOutcome("s1", "order.query", StepOutcome.Status.OK, 5, null,
                        args("orderId", "A10003", "status", "PAID"), List.of()),
                output("s2", "refund.quote", 199.0, 0.0, false),
                new StepOutcome("s3", "refund.submit", StepOutcome.Status.OK, 5, null,
                        refundPayload(199.0), List.of("$s2.quoteId", "$s2.amount")));

        Critique critique = critic.review(plan, t(), outcomes);

        assertThat(critique.missingOutputs()).contains("s1.paidAmount");
        assertThat(critique.verdict()).isEqualTo(Verdict.REPLAN);
    }

    @Test
    @DisplayName("写操作失败 → FAIL:绝不重规划重试,那等于再发一次退款")
    void failedWriteIsFail() {
        Plan plan = clean(t());
        List<StepOutcome> outcomes = List.of(
                output("s1", "order.query", 199.0, 0.0, true),
                output("s2", "refund.quote", 199.0, 0.0, false),
                StepOutcome.failed("s3", "refund.submit", 5, "BUSINESS: 已无可退金额", List.of()));

        Critique critique = critic.review(plan, t(), outcomes);

        assertThat(critique.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(critique.failedSteps()).isNotEmpty();
    }

    @Test
    @DisplayName("事后复核发现不了拆多:多调一次读接口,结果照样正确")
    void redundantIsInvisibleAfterExecution() {
        Plan plan = clean(t());
        List<StepOutcome> outcomes = new java.util.ArrayList<>(healthy());
        outcomes.add(output("s4", "order.query", 199.0, 0.0, true));

        assertThat(critic.review(plan, t(), outcomes).verdict()).isEqualTo(Verdict.PASS);
    }

    private List<StepOutcome> healthy() {
        return List.of(
                output("s1", "order.query", 199.0, 0.0, true),
                output("s2", "refund.quote", 199.0, 0.0, false),
                new StepOutcome("s3", "refund.submit", StepOutcome.Status.OK, 5, null,
                        refundPayload(199.0), List.of("$s2.quoteId", "$s2.amount")));
    }

    private StepOutcome output(String id, String tool, double paid, double refunded, boolean order) {
        ObjectNode payload = order
                ? args("orderId", "A10003", "sku", "SKU-1003", "paidAmount", paid,
                "refundedAmount", refunded, "status", "PAID")
                : args("quoteId", "Q10003", "amount", paid);
        return StepOutcome.ok(id, tool, 5, payload, order ? List.of() : List.of("$s1.paidAmount"));
    }

    private ObjectNode refundPayload(double amount) {
        return args("refundId", "R1", "status", "ACCEPTED", "amount", amount);
    }
}
