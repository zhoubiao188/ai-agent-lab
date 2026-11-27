package com.example.agent.tool;

import com.example.o11y.clock.Clock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简熔断器:每个工具一个,连续失败到阈值就打开,冷却后半开放一个探针。
 *
 * <p>为什么必须有它:重试是"这次救回来",熔断是"别再打了"。只有重试没有熔断,
 * 一个已经挂掉的下游会被每个请求重试三次,把故障放大三倍 —— 这也是很多"雪崩"
 * 的真实成因。</p>
 *
 * <p>单次运行里通常看不到它打开(三次重试还不够触发阈值五),这是正常的:
 * 熔断本来就是跨请求的状态。想验证它,只能靠单测或连续打几次。</p>
 */
public final class CircuitBreaker {

    private static final class State {
        int consecutiveFailures;
        long openUntilNanos;
        boolean halfOpenProbeInFlight;
    }

    private final Clock clock;
    private final int failureThreshold;
    private final long openMs;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CircuitBreaker(Clock clock, int failureThreshold, long openMs) {
        this.clock = clock;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMs = Math.max(1, openMs);
    }

    /** 调用前问一句:现在能不能打。返回 false 表示走降级。 */
    public boolean allow(String tool) {
        State s = states.computeIfAbsent(tool, k -> new State());
        synchronized (s) {
            if (s.openUntilNanos == 0) {
                return true;
            }
            if (clock.nanoTime() < s.openUntilNanos) {
                return false;
            }
            if (s.halfOpenProbeInFlight) {
                return false;
            }
            s.halfOpenProbeInFlight = true;
            return true;
        }
    }

    public void recordSuccess(String tool) {
        State s = states.computeIfAbsent(tool, k -> new State());
        synchronized (s) {
            s.consecutiveFailures = 0;
            s.openUntilNanos = 0;
            s.halfOpenProbeInFlight = false;
        }
    }

    public void recordFailure(String tool) {
        State s = states.computeIfAbsent(tool, k -> new State());
        synchronized (s) {
            s.consecutiveFailures++;
            s.halfOpenProbeInFlight = false;
            if (s.consecutiveFailures >= failureThreshold) {
                s.openUntilNanos = clock.nanoTime() + openMs * 1_000_000L;
                s.consecutiveFailures = 0;
            }
        }
    }

    public boolean isOpen(String tool) {
        State s = states.get(tool);
        if (s == null) {
            return false;
        }
        synchronized (s) {
            return s.openUntilNanos > 0 && clock.nanoTime() < s.openUntilNanos;
        }
    }

    public void reset() {
        states.clear();
    }
}
