package com.example.o11y.clock;

/** 真实时钟。刻意做成无状态单例:服务里到处注入同一个对象,省掉一堆 @Bean。 */
public final class SystemClock implements Clock {

    public static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public long epochMilli() {
        return System.currentTimeMillis();
    }

    @Override
    public void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
