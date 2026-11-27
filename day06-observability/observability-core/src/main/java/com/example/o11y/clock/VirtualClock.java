package com.example.o11y.clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 虚拟时钟:睡眠只推进计数器,不占真实时间。
 *
 * <p>200 次故障注入评测里最耗时的不是算分,而是等超时。虚拟时钟把"等 400ms 超时"变成
 * {@code nanos += 400_000_000},整个评测从分钟级降到毫秒级,而且每次跑出来的 P95 完全一致,
 * 用户提 issue 说"数字对不上"时才解释得清。</p>
 */
public final class VirtualClock implements Clock {

    private final AtomicLong nanos;
    private final long baseEpochMilli;

    public VirtualClock() {
        this(0L, 1_700_000_000_000L);
    }

    public VirtualClock(long startNanos, long baseEpochMilli) {
        this.nanos = new AtomicLong(startNanos);
        this.baseEpochMilli = baseEpochMilli;
    }

    @Override
    public long nanoTime() {
        return nanos.get();
    }

    @Override
    public long epochMilli() {
        return baseEpochMilli + nanos.get() / 1_000_000L;
    }

    @Override
    public void sleep(long millis) {
        if (millis > 0) {
            nanos.addAndGet(millis * 1_000_000L);
        }
    }
}
