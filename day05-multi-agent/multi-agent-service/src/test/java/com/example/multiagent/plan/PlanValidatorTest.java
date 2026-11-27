package com.example.multiagent.plan;

import com.example.multiagent.support.BaseTest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest extends BaseTest {

    @Test
    @DisplayName("干净的售后全链路计划:零违规")
    void cleanPlanPasses() {
        Plan plan = clean(task("T01"));

        assertThat(validator.validate(plan)).isEmpty();
        assertThat(plan.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("环:A 依赖 B、B 依赖 A —— 计划不可执行,而且列表结构根本表达不出来")
    void cycle() {
        Plan plan = Plan.of(
                new Step("s1", "一", "order.query", args("orderId", "A10001"),
                        List.of("s2"), List.of("order"), List.of("orderId"), List.of()),
                new Step("s2", "二", "logistics.track", args("orderId", "A10001"),
                        List.of("s1"), List.of("logistics"), List.of("latest"), List.of()));

        assertThat(plan.topological()).isEmpty();
        assertThat(codes(plan)).contains(ViolationCode.CYCLE);
    }

    @Test
    @DisplayName("依赖了不存在的步骤")
    void danglingDependency() {
        Plan plan = clean(task("T01"));
        Step ticket = plan.find("s5").orElseThrow();
        Plan broken = plan.replace(ticket.withDependsOn(List.of("s99")));

        assertThat(codes(broken)).contains(ViolationCode.DANGLING_DEPENDENCY);
    }

    @Test
    @DisplayName("参数引用了上游,却没写进 dependsOn —— 顺序错最典型的机器可读形态")
    void missingDependency() {
        Plan plan = clean(task("T03"));
        Step refund = plan.find("s3").orElseThrow();
        assertThat(refund.refs()).isNotEmpty();

        Plan broken = plan.replace(refund.withDependsOn(List.of()));

        assertThat(codes(broken)).contains(ViolationCode.MISSING_DEPENDENCY);
    }

    @Test
    @DisplayName("引用了一个没人产出的字段")
    void unproducedReference() {
        Plan plan = clean(task("T03"));
        Step quote = plan.find("s2").orElseThrow();
        ObjectNode badArgs = quote.args().deepCopy();
        badArgs.put("paidAmount", "$s1.notExists");

        Plan broken = plan.replace(quote.withArgs(badArgs));

        assertThat(codes(broken)).contains(ViolationCode.UNPRODUCED_REFERENCE);
    }

    @Test
    @DisplayName("同一工具 + 同一参数出现两次:拆多")
    void redundantStep() {
        Plan plan = clean(task("T01")).plus(new Step("s9", "再查一次", "order.query",
                args("orderId", "A10001"), List.of(), List.of("order"),
                List.of("orderId"), List.of()));

        assertThat(codes(plan)).contains(ViolationCode.REDUNDANT_STEP);
    }

    @Test
    @DisplayName("步骤数超过上限")
    void tooManySteps() {
        Plan plan = Plan.empty();
        for (int i = 1; i <= 9; i++) {
            plan = plan.plus(new Step("s" + i, "工单 " + i, "ticket.create",
                    args("orderId", "A1000" + i, "category", "refund"), List.of(), List.of(), List.of(), List.of()));
        }

        assertThat(codes(plan)).contains(ViolationCode.TOO_MANY_STEPS);
    }

    @Test
    @DisplayName("退款金额是写死的字面量 —— 最贵的一种错,它会真的退出去一笔钱")
    void ungroundedWrite() {
        Plan plan = clean(task("T03"));
        Step refund = plan.find("s3").orElseThrow();
        ObjectNode literal = args("quoteId", "Q00000", "amount", 199.0, "reason", "七天无理由");

        Plan broken = plan.replace(refund.withArgs(literal).withDependsOn(List.of()));

        assertThat(codes(broken)).contains(ViolationCode.UNGROUNDED_WRITE);
    }

    @Test
    @DisplayName("工具没注册")
    void unknownTool() {
        Plan plan = clean(task("T03"));
        Step quote = plan.find("s2").orElseThrow();

        assertThat(codes(plan.replace(quote.withTool("refund.estimate"))))
                .contains(ViolationCode.UNKNOWN_TOOL);
    }

    @Test
    @DisplayName("空计划不通过,且不抛异常")
    void emptyPlan() {
        assertThat(validator.validate(Plan.empty())).isNotEmpty();
    }

    @Test
    @DisplayName("校验报告带 stepId,前端才能把具体卡片标红")
    void violationsCarryStepId() {
        Plan plan = clean(task("T03"));
        Step refund = plan.find("s3").orElseThrow();
        Plan broken = plan.replace(refund.withDependsOn(List.of()));

        assertThat(validator.validate(broken))
                .filteredOn(v -> v.code() == ViolationCode.MISSING_DEPENDENCY)
                .allMatch(v -> "s3".equals(v.stepId()));
    }

    private List<ViolationCode> codes(Plan plan) {
        return validator.validate(plan).stream().map(PlanViolation::code).toList();
    }
}
