package com.example.multiagent.exec;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单工具熔断器(时间滑动窗口 + 半开探测)。
 *
 * <p>为什么必须有它:重试解决"偶发失败",但解决不了"依赖已经死了"。
 * 当下游整体挂掉,重试 × 并发会让每个请求都排队等满超时,
 * P99 直接从 300ms 冲到 3s,线程池被拖满,连健康接口都返回不了。
 * 熔断的作用是把"慢失败"换成"快失败"——让用户 5ms 内看到降级结果。</p>
 *
 * <p>参数上最容易踩的坑是 {@code minCalls} 设太小:低流量工具在 3 次调用里挂 2 次就被熔断,
 * 恢复还要等冷却期,可用性反而更差。20 次是低流量下的下限。</p>
 */
public class CircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private record Event(long ts, boolean failure) {
    }

    public record Snapshot(State state, int windowCalls, int windowFailures, double windowFailureRate,
                           long openRemainingMs, long rejected) {
    }

    private final String name;
    private final long windowMs;
    private final int minCalls;
    private final double failureRateThreshold;
    private final long openMs;
    private final int halfOpenProbes;

    private final Deque<Event> window = new ArrayDeque<>();
    private State state = State.CLOSED;
    private long openedAt;
    private int probesInFlight;
    private long rejected;

    public CircuitBreaker(String name, long windowMs, int minCalls, double failureRateThreshold,
                          long openMs, int halfOpenProbes) {
        this.name = name;
        this.windowMs = windowMs;
        this.minCalls = minCalls;
        this.failureRateThreshold = failureRateThreshold;
        this.openMs = openMs;
        this.halfOpenProbes = halfOpenProbes;
    }

    /** @return true 表示放行;false 表示快速失败,不要再打上游 */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now - openedAt >= openMs) {
                state = State.HALF_OPEN;
                probesInFlight = 0;
            } else {
                rejected++;
                return false;
            }
        }
        if (state == State.HALF_OPEN) {
            if (probesInFlight >= halfOpenProbes) {
                rejected++;
                return false;
            }
            probesInFlight++;
        }
        return true;
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            probesInFlight = 0;
            window.clear();
            window.addLast(new Event(System.currentTimeMillis(), false));
            return;
        }
        add(false);
        trim();
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            probesInFlight = 0;
            add(true);
            return;
        }
        add(true);
        trim();
        if (state == State.CLOSED && window.size() >= minCalls
                && failureRate() >= failureRateThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
        }
    }

    public synchronized Snapshot snapshot() {
        long remain = state == State.OPEN
                ? Math.max(0, openMs - (System.currentTimeMillis() - openedAt))
                : 0;
        trim();
        return new Snapshot(state, window.size(), failures(), failureRate(), remain, rejected);
    }

    public synchronized void reset() {
        state = State.CLOSED;
        openedAt = 0;
        probesInFlight = 0;
        rejected = 0;
        window.clear();
    }

    public String name() {
        return name;
    }

    private void add(boolean failure) {
        window.addLast(new Event(System.currentTimeMillis(), failure));
    }

    private void trim() {
        long cutoff = System.currentTimeMillis() - windowMs;
        while (!window.isEmpty() && window.peekFirst().ts() < cutoff) {
            window.removeFirst();
        }
    }

    private int failures() {
        int n = 0;
        for (Event e : window) {
            if (e.failure()) {
                n++;
            }
        }
        return n;
    }

    private double failureRate() {
        int total = window.size();
        return total == 0 ? 0 : (double) failures() / total;
    }
}
