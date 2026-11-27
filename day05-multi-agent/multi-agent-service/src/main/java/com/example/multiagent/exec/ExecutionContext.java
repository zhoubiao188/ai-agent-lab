package com.example.multiagent.exec;

import com.example.multiagent.agent.EventSink;

/**
 * 一次任务执行期间共享的上下文。
 *
 * <p>{@code deadlineEpochMs} 是整条链路的预算,而不是单个工具的超时。
 * 两者必须都设:单工具超时防止一个坏依赖拖死全场,整体预算防止"重试 × 工具数"
 * 把一次用户请求拖成 40 秒。</p>
 */
public record ExecutionContext(
        String requestId,
        String callId,
        String idempotencyKey,
        long deadlineEpochMs,
        long startEpochMs,
        EventSink sink
) {

    public long remainingMs() {
        return deadlineEpochMs - System.currentTimeMillis();
    }

    public boolean budgetExhausted(long reserveMs) {
        return remainingMs() <= reserveMs;
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startEpochMs;
    }

    public ExecutionContext withCall(String newCallId, String newIdempotencyKey) {
        return new ExecutionContext(requestId, newCallId, newIdempotencyKey,
                deadlineEpochMs, startEpochMs, sink);
    }

    public static ExecutionContext of(String requestId, long budgetMs) {
        long now = System.currentTimeMillis();
        return new ExecutionContext(requestId, "-", "-", now + budgetMs, now, EventSink.NOOP);
    }
}
