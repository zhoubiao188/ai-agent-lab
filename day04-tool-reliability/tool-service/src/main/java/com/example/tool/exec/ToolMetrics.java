package com.example.tool.exec;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单工具指标。刻意不引 Micrometer 的 Timer:这里要的是"这次任务里发生了什么",
 * 而且要在评测里能一键清零重来。Micrometer 负责对外暴露,两者不冲突。
 *
 * <p>P95 用固定容量环形缓冲算近似值(最近 512 次),够用且不会随流量增长吃内存。
 * 全量直方图在生产上更准,但这里没有那个必要。</p>
 */
public class ToolMetrics {

    private static final int CAPACITY = 512;

    private final LongAdder calls = new LongAdder();
    private final LongAdder successes = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder repairs = new LongAdder();
    private final LongAdder circuitRejects = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();

    private final long[] latencies = new long[CAPACITY];
    private int cursor;
    private int filled;

    public record Snapshot(long calls, long successes, long failures, long retries, long repairs,
                           long circuitRejects, long fallbacks, long p50Ms, long p95Ms, long maxMs) {
    }

    public void onCall() {
        calls.increment();
    }

    public void onSuccess() {
        successes.increment();
    }

    public void onFailure() {
        failures.increment();
    }

    public void onRetry() {
        retries.increment();
    }

    public void onRepair(int count) {
        repairs.add(count);
    }

    public void onCircuitReject() {
        circuitRejects.increment();
    }

    public void onFallback() {
        fallbacks.increment();
    }

    public synchronized void recordLatency(long ms) {
        latencies[cursor] = ms;
        cursor = (cursor + 1) % CAPACITY;
        if (filled < CAPACITY) {
            filled++;
        }
    }

    public synchronized Snapshot snapshot() {
        long[] copy = Arrays.copyOf(latencies, filled);
        Arrays.sort(copy);
        return new Snapshot(calls.sum(), successes.sum(), failures.sum(), retries.sum(),
                repairs.sum(), circuitRejects.sum(), fallbacks.sum(),
                percentile(copy, 0.50), percentile(copy, 0.95),
                copy.length == 0 ? 0 : copy[copy.length - 1]);
    }

    private long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    public synchronized void reset() {
        calls.reset();
        successes.reset();
        failures.reset();
        retries.reset();
        repairs.reset();
        circuitRejects.reset();
        fallbacks.reset();
        cursor = 0;
        filled = 0;
    }
}
