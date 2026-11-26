package com.example.tool.exec;

import com.example.tool.spec.JsonSchema;
import com.example.tool.spec.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.tool.support.TestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(props());
    private final JsonSchema schema = JsonSchema.object(
            new LinkedHashMap<>(Map.of("id", JsonSchema.string(""))), "id");

    private final ToolSpec read = ToolSpec.read("order.query", "", schema);
    private final ToolSpec idempotentWrite = ToolSpec.write("refund.submit", "", schema, true);
    private final ToolSpec plainWrite = ToolSpec.write("ticket.create", "", schema, false);

    @Test
    void 超时可以重试且退避有上限() {
        RetryPolicy.Decision d = policy.decide(1, ToolException.timeout(300), read);
        assertThat(d.retry()).isTrue();
        assertThat(d.delayMs()).isBetween(1L, 80L);
    }

    @Test
    void 查不到和业务拒绝不该重试() {
        assertThat(policy.decide(1, ToolException.notFound("no order"), read).retry()).isFalse();
        assertThat(policy.decide(1, ToolException.business("库存不足"), read).retry()).isFalse();
        assertThat(policy.decide(1, ToolException.badArgument("id 为空"), read).retry()).isFalse();
    }

    @Test
    void 非幂等写操作一次都不重试() {
        RetryPolicy.Decision d = policy.decide(1, ToolException.timeout(300), plainWrite);
        assertThat(d.retry()).isFalse();
        assertThat(d.reason()).contains("非幂等");
    }

    @Test
    void 幂等写操作可以重试() {
        assertThat(policy.decide(1, ToolException.timeout(300), idempotentWrite).retry()).isTrue();
    }

    @Test
    void RetryAfter超过退避上限时放弃() {
        // 上游说 5 秒后再来,我 40ms 后就去敲只会拿回另一个 429
        RetryPolicy.Decision d = policy.decide(1, ToolException.rateLimited(5000), read);
        assertThat(d.retry()).isFalse();
        assertThat(d.reason()).contains("Retry-After");
    }

    @Test
    void RetryAfter在上限内必须尊重() {
        RetryPolicy.Decision d = policy.decide(1, ToolException.rateLimited(60), read);
        assertThat(d.retry()).isTrue();
        assertThat(d.delayMs()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void 达到最大次数就停() {
        assertThat(policy.decide(3, ToolException.timeout(300), read).retry()).isFalse();
    }

    @Test
    void 退避是指数增长但不超过上限() {
        assertThat(policy.backoff(1)).isLessThanOrEqualTo(10);
        assertThat(policy.backoff(2)).isLessThanOrEqualTo(20);
        assertThat(policy.backoff(9)).isLessThanOrEqualTo(80);
    }
}
