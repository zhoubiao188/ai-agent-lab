package com.example.tool.exec;

/**
 * 一次任务的执行策略。评测里的 L0~L3 就是同一个编排器跑不同的策略,
 * 这样对比的才是"机制带来的差异",而不是四份实现之间的差异。
 */
public record RunPolicy(
        String name,
        boolean retry,
        boolean repair,
        boolean parallel,
        boolean breaker,
        boolean fallback
) {

    public static final RunPolicy BASELINE = new RunPolicy("L0-baseline", false, false, false, false, false);
    public static final RunPolicy RETRY = new RunPolicy("L1-retry", true, false, false, false, false);
    public static final RunPolicy RETRY_REPAIR = new RunPolicy("L2-retry+repair", true, true, false, false, false);
    public static final RunPolicy FULL = new RunPolicy("L3-full", true, true, true, true, true);

    public static RunPolicy of(String name, boolean retry, boolean repair, boolean parallel,
                               boolean breaker, boolean fallback) {
        return new RunPolicy(name, retry, repair, parallel, breaker, fallback);
    }
}
