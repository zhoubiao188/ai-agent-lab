package com.example.memory.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedFactsTest {

    @Test
    @DisplayName("单号、金额、日期、手机号都能被原文保真提取")
    void extractKeyFacts() {
        String text = "订单号 A20261123001 的退款 899.00 元什么时候到?我手机 13800138000,"
                + "承诺 2026-11-25 前到账,超时按 5% 赔付。";

        List<String> facts = PinnedFacts.extract(text);

        assertThat(facts).anyMatch(f -> f.contains("A20261123001"));
        assertThat(facts).anyMatch(f -> f.contains("899.00"));
        assertThat(facts).anyMatch(f -> f.contains("2026-11-25"));
        assertThat(facts).anyMatch(f -> f.contains("13800138000"));
        assertThat(facts).anyMatch(f -> f.contains("5%"));
    }

    @Test
    @DisplayName("没有数字的闲聊不产生噪音事实")
    void noFactsOnSmallTalk() {
        assertThat(PinnedFacts.extract("你好,我想了解一下你们的服务")).isEmpty();
        assertThat(PinnedFacts.extract("")).isEmpty();
        assertThat(PinnedFacts.extract((String) null)).isEmpty();
    }

    @Test
    @DisplayName("同一事实重复出现只保留一条")
    void dedupe() {
        List<String> facts = PinnedFacts.extract("订单号 A20261123001,再说一次订单号 A20261123001");
        assertThat(facts.stream().filter(f -> f.contains("A20261123001")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("单轮最多 8 条,避免把 system prompt 撑爆")
    void capped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("订单号 A20261123").append(100 + i).append(" ");
        }
        assertThat(PinnedFacts.extract(sb.toString())).hasSizeLessThanOrEqualTo(8);
    }
}
