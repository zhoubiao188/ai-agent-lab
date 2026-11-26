package com.example.tool.exec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private CircuitBreaker breaker() {
        return new CircuitBreaker("order.query", 2000, 5, 0.5, 150, 1);
    }

    @Test
    void 失败率达到阈值后打开() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryAcquire()).isTrue();
            b.onFailure();
        }
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire()).isFalse();
        assertThat(b.snapshot().rejected()).isEqualTo(1);
    }

    @Test
    void 样本不足时不熔断() {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 3; i++) {
            b.tryAcquire();
            b.onFailure();
        }
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 半开只放一个探测请求() throws Exception {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) {
            b.tryAcquire();
            b.onFailure();
        }
        Thread.sleep(180);
        assertThat(b.tryAcquire()).isTrue();
        // 探测还没回来,第二个请求必须被挡在门外,否则恢复瞬间会被流量打穿
        assertThat(b.tryAcquire()).isFalse();
        b.onSuccess();
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void 探测失败会重新打开并重新计时() throws Exception {
        CircuitBreaker b = breaker();
        for (int i = 0; i < 5; i++) {
            b.tryAcquire();
            b.onFailure();
        }
        Thread.sleep(180);
        assertThat(b.tryAcquire()).isTrue();
        b.onFailure();
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire()).isFalse();
    }

    @Test
    void 成功会稀释失败率() {
        CircuitBreaker b = breaker();
        b.tryAcquire();
        b.onFailure();
        for (int i = 0; i < 5; i++) {
            b.tryAcquire();
            b.onSuccess();
        }
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.snapshot().windowFailureRate()).isLessThan(0.5);
    }

    @Test
    void 窗口外的旧事件会被清掉() throws Exception {
        CircuitBreaker b = new CircuitBreaker("t", 100, 5, 0.5, 1000, 1);
        for (int i = 0; i < 5; i++) {
            b.tryAcquire();
            b.onFailure();
        }
        assertThat(b.snapshot().state()).isEqualTo(CircuitBreaker.State.OPEN);
        Thread.sleep(120);
        // 冷却时间还没到,但窗口里的旧失败已经被裁掉,失败率归零
        assertThat(b.snapshot().windowCalls()).isZero();
    }
}
