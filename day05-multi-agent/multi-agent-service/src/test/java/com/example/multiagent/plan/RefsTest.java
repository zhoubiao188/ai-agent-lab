package com.example.multiagent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefsTest {

    private final JsonNodeFactory json = JsonNodeFactory.instance;

    @Test
    @DisplayName("扫描出参数里全部引用,并去重")
    void scan() {
        ObjectNode args = json.objectNode();
        args.put("orderId", "A10001");
        args.put("paidAmount", "$s1.paidAmount");
        args.put("note", "$s1.refundedAmount");
        args.put("again", "$s1.paidAmount");

        List<Refs.Ref> refs = Refs.scan(args);

        assertThat(refs).containsExactly(
                new Refs.Ref("s1", "paidAmount"),
                new Refs.Ref("s1", "refundedAmount"));
    }

    @Test
    @DisplayName("嵌套对象和数组里的引用也要扫到")
    void scanNested() {
        ObjectNode inner = json.objectNode();
        inner.put("amount", "$s2.amount");
        ObjectNode args = json.objectNode();
        args.set("payload", inner);
        args.set("list", json.arrayNode().add("$s3.quoteId"));

        assertThat(Refs.scan(args)).extracting(Refs.Ref::toString)
                .containsExactly("$s2.amount", "$s3.quoteId");
    }

    @Test
    @DisplayName("整体匹配的引用才替换,不会被替换成半个字符串")
    void resolveWholeValue() {
        ObjectNode args = json.objectNode();
        args.put("amount", "$s1.amount");
        ObjectNode values = json.objectNode();
        values.put("amount", 199.5);

        ObjectNode resolved = Refs.resolve(args, ref -> values.get(ref.field()));

        assertThat(resolved.get("amount").asDouble()).isEqualTo(199.5);
        assertThat(resolved.get("amount").isNumber()).isTrue();
    }

    @Test
    @DisplayName("解析不到就原样保留,由调用层报错,而不是悄悄变成 null")
    void resolveMissingKeepsRef() {
        ObjectNode args = json.objectNode();
        args.put("amount", "$s9.amount");

        ObjectNode resolved = Refs.resolve(args, ref -> null);

        assertThat(resolved.get("amount").asText()).isEqualTo("$s9.amount");
    }

    @Test
    @DisplayName("resolve 不改原始参数对象")
    void resolveDoesNotMutate() {
        ObjectNode args = json.objectNode();
        args.put("amount", "$s1.amount");
        ObjectNode values = json.objectNode();
        values.put("amount", 1.0);

        Refs.resolve(args, ref -> values.get(ref.field()));

        assertThat(args.get("amount").asText()).isEqualTo("$s1.amount");
    }

    @Test
    @DisplayName("引用的字段可以是 JsonNode 任意类型")
    void resolveKeepsType() {
        ObjectNode args = json.objectNode();
        args.put("enough", "$s1.enough");
        ObjectNode values = json.objectNode();
        values.put("enough", true);
        JsonNode v = Refs.resolve(args, ref -> values.get(ref.field())).get("enough");

        assertThat(v.isBoolean()).isTrue();
        assertThat(v.asBoolean()).isTrue();
    }
}
