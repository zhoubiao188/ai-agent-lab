package com.example.agent.pipeline;

import com.example.agent.fault.FaultInjector;
import com.example.agent.fault.FaultPlan;
import com.example.agent.fault.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 槽位绑定与参数纠偏。
 *
 * <p>这里最容易被当成"小事"的一步,恰恰是退款金额出错的源头:
 * 中文数字、"退 88" 这种省略单位的说法、缺失金额时的默认值,三条路都会通到下游。</p>
 */
class SlotBinderTest {

    private final SlotBinder binder = new SlotBinder();
    private final FaultInjector injector = new FaultInjector(1L);

    private SlotBinder.BindResult bind(String question, Scenario scenario) {
        FaultPlan plan = injector.plan(scenario, "T-1");
        return binder.bind(question, "u1", plan, com.example.agent.pipeline.Intent.REFUND);
    }

    @Test
    @DisplayName("带单位的金额按元解析成分")
    void amountWithUnit() {
        var result = bind("订单 ORD-100234 退款 299 元,不想要了", Scenario.NORMAL);
        assertThat(result.slots().integer("amountCents")).isEqualTo(29900);
        assertThat(result.slots().source("amountCents")).isEqualTo(Slots.TEXT);
        assertThat(result.defaults()).isEmpty();
        assertThat(result.confidence()).isGreaterThan(0.9);
    }

    @ParameterizedTest
    @CsvSource({
            "退 88, 8800",
            "退一百二, 12000",
            "退两百元, 20000",
            "退 1280 块, 128000",
            "退三十元, 3000"
    })
    @DisplayName("省略单位与中文数字都能落到分")
    void amountVariants(String text, int expectedCents) {
        var result = bind("订单 ORD-100236 " + text, Scenario.NORMAL);
        assertThat(result.slots().integer("amountCents")).isEqualTo(expectedCents);
    }

    @Test
    @DisplayName("缺金额时走默认值,置信度掉到\"该追问\"区间,并记录来源")
    void missingAmountFallsBackToDefault() {
        var result = bind("订单 ORD-100237 要退款", Scenario.ARG_DRIFT);
        assertThat(result.slots().integer("amountCents")).isZero();
        assertThat(result.slots().source("amountCents")).isEqualTo(Slots.DEFAULT);
        assertThat(result.defaults()).contains("amountCents");
        assertThat(result.confidence()).isLessThan(0.55);
    }

    @Test
    @DisplayName("只说\"这个订单\"时从记忆里取上一单,来源标记为 MEMORY")
    void memoryRecall() {
        binder.bind("订单 ORD-100235 退款 100 元", "u9", injector.plan(Scenario.NORMAL, "T"), Intent.REFUND);
        var result = binder.bind("这个订单也要退款,200 元", "u9",
                injector.plan(Scenario.NORMAL, "T"), Intent.REFUND);
        assertThat(result.slots().string("orderId")).isEqualTo("ORD-100235");
        assertThat(result.slots().source("orderId")).isEqualTo(Slots.MEMORY);
    }

    @Test
    @DisplayName("政策类意图不需要槽位,不应该被判成\"缺 3 个参数\"")
    void docIntentNeedsNoSlots() {
        var result = binder.bind("退款政策怎么规定的", "u1",
                injector.plan(Scenario.NORMAL, "T"), Intent.DOC);
        assertThat(result.required()).isEmpty();
        assertThat(result.defaults()).isEmpty();
        assertThat(result.confidence()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("金额超过实付时不在这里拦,留给 D16 自检 —— 每层只做一件事")
    void binderDoesNotValidateBusinessRules() {
        var result = bind("订单 ORD-100235 退款 9999 元", Scenario.NORMAL);
        assertThat(result.slots().integer("amountCents")).isEqualTo(999900);
        assertThat(result.confidence()).isGreaterThan(0.9);
    }
}
