package com.example.agent.tool;

import com.example.agent.fault.FaultPlan;
import com.example.agent.fault.Scenario;
import com.example.o11y.clock.Clock;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次运行的上下文:故障计划 + 埋点定位 + 计时。
 *
 * <p>把"标准答案"放在这里,是因为评测需要它;但 Tracer 和 TriageEngine 都拿不到这个对象,
 * 排序算法**看不到答案**,否则评测毫无意义。这条边界要写死在代码结构里,不能靠自觉。</p>
 */
public final class RunContext {

    private final Scenario scenario;
    private final FaultPlan plan;
    private final Clock clock;
    private final String taskId;
    private final AtomicReference<String> culpritSpanId = new AtomicReference<>();
    private final AtomicReference<String> culpritReason = new AtomicReference<>();

    public RunContext(Scenario scenario, FaultPlan plan, Clock clock, String taskId) {
        this.scenario = scenario;
        this.plan = plan;
        this.clock = clock;
        this.taskId = taskId;
    }

    public Scenario scenario() {
        return scenario;
    }

    public FaultPlan plan() {
        return plan;
    }

    public Clock clock() {
        return clock;
    }

    public String taskId() {
        return taskId;
    }

    /** 埋点第一次真正触发时,记下它落在哪个 Span 上。只记第一次,避免重试把答案覆盖。 */
    public void noteCulprit(String spanId, String reason) {
        if (culpritSpanId.compareAndSet(null, spanId)) {
            culpritReason.set(reason);
        }
    }

    public String culpritSpanId() {
        return culpritSpanId.get();
    }

    public String culpritReason() {
        return culpritReason.get();
    }

    public boolean faultFired() {
        return culpritSpanId.get() != null;
    }
}
