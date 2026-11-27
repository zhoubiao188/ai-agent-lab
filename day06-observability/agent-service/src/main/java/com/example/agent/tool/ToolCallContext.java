package com.example.agent.tool;

/**
 * 单次工具调用的上下文:第几次尝试、还剩多少预算、有没有埋故障。
 *
 * <p>工具实现里不要出现 {@code Thread.sleep(500)}。延迟必须走这里,才能被虚拟时钟接管,
 * 也才能被"慢工具"剧本按需放大。</p>
 */
public final class ToolCallContext {

    private final RunContext run;
    private final String tool;
    private final int attempt;
    private final long timeoutMs;

    public ToolCallContext(RunContext run, String tool, int attempt, long timeoutMs) {
        this.run = run;
        this.tool = tool;
        this.attempt = attempt;
        this.timeoutMs = Math.max(1, timeoutMs);
    }

    public RunContext run() {
        return run;
    }

    public int attempt() {
        return attempt;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    /**
     * 消耗一次模拟网络延迟。慢工具剧本会把延迟放大到 timeout 之上,
     * 但**不许睡过自己的预算**:真实系统里客户端会在超时点断开,不会等你睡完。
     */
    public void latency(long baseMs) {
        long delay = baseMs;
        if (run.plan().affectsSlow(tool)) {
            delay = Math.max(delay, run.plan().slowMs());
        }
        run.clock().sleep(Math.min(delay, timeoutMs));
    }

    /** 故障剧本命中:该工具的第 attempt 次调用要报错。 */
    public boolean shouldFail() {
        return run.plan().affectsFlaky(tool) && attempt <= run.plan().flakyTimes();
    }

    public ErrorKind injectedError() {
        ErrorKind kind = run.plan().flakyKind();
        return kind == null ? ErrorKind.UPSTREAM_5XX : kind;
    }

    /** 是否是"慢工具"剧本的目标工具。用于埋点定位与页面提示。 */
    public boolean isSlowTarget() {
        return run.plan().affectsSlow(tool);
    }
}
