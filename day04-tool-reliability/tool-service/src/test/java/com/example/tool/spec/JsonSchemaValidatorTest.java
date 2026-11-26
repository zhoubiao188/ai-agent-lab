package com.example.tool.spec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaValidatorTest {

    private final JsonSchemaValidator validator = new JsonSchemaValidator();

    private final JsonSchema schema = JsonSchema.object(new LinkedHashMap<>(Map.of(
            "orderId", JsonSchema.string("订单号").withPattern("A\\d{5}"),
            "amount", JsonSchema.number("金额", 0.01, 1000),
            "reason", JsonSchema.enumOf("原因", "质量问题", "物流破损")
    )), "orderId", "amount", "reason");

    private List<Violation> validate(String json) throws Exception {
        JsonNode node = TestJson.MAPPER.readTree(json);
        return validator.validate(node, schema);
    }

    @Test
    void 合法参数没有任何错误() throws Exception {
        assertThat(validate("""
                {"orderId":"A10001","amount":199.0,"reason":"质量问题"}
                """)).isEmpty();
    }

    @Test
    void 缺必填字段能定位到具体路径() throws Exception {
        List<Violation> v = validate("""
                {"orderId":"A10001","amount":199.0}
                """);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).path()).isEqualTo("/reason");
        assertThat(v.get(0).code()).isEqualTo(Violation.Code.MISSING_REQUIRED);
        assertThat(v.get(0).blocking()).isTrue();
    }

    @Test
    void 类型不匹配会报出期望和实际() throws Exception {
        List<Violation> v = validate("""
                {"orderId":"A10001","amount":"199.0","reason":"质量问题"}
                """);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(Violation.Code.TYPE_MISMATCH);
        assertThat(v.get(0).message()).contains("期望 number").contains("string");
    }

    @Test
    void 超出区间会被拦下() throws Exception {
        List<Violation> v = validate("""
                {"orderId":"A10001","amount":9999,"reason":"质量问题"}
                """);
        assertThat(v).extracting(Violation::code).containsExactly(Violation.Code.OUT_OF_RANGE);
    }

    @Test
    void 枚举与模式都要校验() throws Exception {
        List<Violation> v = validate("""
                {"orderId":"X1","amount":10,"reason":"不想要了"}
                """);
        assertThat(v).extracting(Violation::code)
                .containsExactlyInAnyOrder(Violation.Code.PATTERN_MISMATCH, Violation.Code.ENUM_MISMATCH);
    }

    @Test
    void 未知字段只告警不阻塞() throws Exception {
        List<Violation> v = validate("""
                {"orderId":"A10001","amount":10,"reason":"质量问题","timestamp":1}
                """);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).code()).isEqualTo(Violation.Code.UNKNOWN_FIELD);
        assertThat(v.get(0).blocking()).isFalse();
    }

    @Test
    void 校验器绝不偷偷做类型转换() throws Exception {
        // 这是刻意的设计:转换是修复器的活,校验器只负责说实话
        List<Violation> v = validate("""
                {"orderId":"A10001","amount":"199","reason":"质量问题"}
                """);
        assertThat(v).anyMatch(x -> x.code() == Violation.Code.TYPE_MISMATCH);
    }

    static final class TestJson {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
