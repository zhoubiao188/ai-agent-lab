package com.example.tool.repair;

import com.example.tool.spec.JsonSchema;
import com.example.tool.spec.Violation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.tool.support.TestSupport.obj;
import static org.assertj.core.api.Assertions.assertThat;

class ArgumentRepairerTest {

    private final ArgumentRepairer repairer = new ArgumentRepairer();

    private final JsonSchema schema = JsonSchema.object(new LinkedHashMap<>(Map.of(
            "orderId", JsonSchema.string("订单号").withPattern("A\\d{5}"),
            "amount", JsonSchema.number("金额", 0.01, 1000),
            "quantity", JsonSchema.integer("数量", 1, 99),
            "reason", JsonSchema.enumOf("原因", "质量问题", "物流破损"),
            "urgent", JsonSchema.bool("加急")
    )), "orderId", "amount", "reason");

    @Test
    void 键名风格不一致能自动对齐() {
        ObjectNode args = obj("order_id", "A10001", "amount", 199, "reason", "质量问题");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.clean()).isTrue();
        assertThat(out.args().get("orderId").asText()).isEqualTo("A10001");
        assertThat(out.args().has("order_id")).isFalse();
        assertThat(out.applied()).extracting(Repair::rule).contains("KEY_ALIAS");
    }

    @Test
    void 数字被写成字符串能转回来() {
        ObjectNode args = obj("orderId", "A10001", "amount", "199.5", "reason", "质量问题");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.clean()).isTrue();
        assertThat(out.args().get("amount").asDouble()).isEqualTo(199.5);
        assertThat(out.applied()).extracting(Repair::rule).contains("STRING_TO_NUMBER");
    }

    @Test
    void 枚举大小写能归一() {
        ObjectNode args = obj("orderId", "A10001", "amount", 10, "reason", "质量问题");
        args.put("reason", "  物流破损 ");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.clean()).isTrue();
        assertThat(out.args().get("reason").asText()).isEqualTo("物流破损");
    }

    @Test
    void 多余字段直接丢弃() {
        ObjectNode args = obj("orderId", "A10001", "amount", 10, "reason", "质量问题", "trace", "x");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.args().has("trace")).isFalse();
        assertThat(out.applied()).extracting(Repair::rule).contains("DROP_UNKNOWN");
    }

    @Test
    void 布尔字符串能转成布尔() {
        ObjectNode args = obj("orderId", "A10001", "amount", 10, "reason", "质量问题", "urgent", "true");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.args().get("urgent").isBoolean()).isTrue();
        assertThat(out.args().get("urgent").asBoolean()).isTrue();
    }

    @Test
    void 小数转整数必须拒绝绝不截断() {
        // 12.7 转成 12 是资金事故的起点,这条规则宁可让调用失败
        ObjectNode args = obj("orderId", "A10001", "amount", 10, "reason", "质量问题", "quantity", "12.7");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.clean()).isFalse();
        assertThat(out.remaining()).extracting(Violation::code)
                .contains(Violation.Code.TYPE_MISMATCH);
    }

    @Test
    void 缺必填字段修复器不会瞎猜() {
        // 只有编排层拿着任务原文才有资格补,修复器只做机械变换
        ObjectNode args = obj("amount", 10, "reason", "质量问题");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.clean()).isFalse();
        assertThat(out.remaining()).extracting(Violation::path).contains("/orderId");
    }

    @Test
    void 本来就对的参数不产生任何修复记录() {
        ObjectNode args = obj("orderId", "A10001", "amount", 199, "reason", "质量问题");
        RepairOutcome out = repairer.repair(args, schema);

        assertThat(out.applied()).isEmpty();
        assertThat(out.changed()).isFalse();
        assertThat(out.clean()).isTrue();
    }
}
