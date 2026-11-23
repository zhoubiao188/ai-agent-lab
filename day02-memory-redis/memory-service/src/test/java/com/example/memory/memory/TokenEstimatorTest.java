package com.example.memory.memory;

import com.example.memory.domain.Msg;
import com.example.memory.domain.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    @DisplayName("中文按 1 字 ≈ 1 token,英文按 3.6 字符 ≈ 1 token")
    void estimateByLanguage() {
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        // 36 个英文字符 ≈ 10 token
        int latin = TokenEstimator.estimate("abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(latin).isBetween(9, 11);
    }

    @Test
    @DisplayName("消息级估算包含角色开销与工具调用开销")
    void estimateMessage() {
        Msg plain = Msg.user(1, "你好");
        Msg withTool = Msg.assistant(2, null,
                List.of(ToolCall.of("c1", "calculator", "{\"expression\":\"1+1\"}")));

        assertThat(TokenEstimator.estimate(plain)).isEqualTo(4 + 2);
        assertThat(TokenEstimator.estimate(withTool)).isGreaterThan(14);
    }

    @Test
    @DisplayName("混合中英文不会算成两倍")
    void mixedText() {
        int tokens = TokenEstimator.estimate("用 Spring Boot 写一个 Agent,共 50 轮对话");
        assertThat(tokens).isBetween(12, 30);
    }
}
