package com.example.o11y.sample;

import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 尾部采样:等一次运行结束,拿到了结论再决定留不留。
 *
 * <p>头部采样(接到请求就掷骰子)在这类系统里非常坑:90% 的成功请求留下来了,
 * 那 10% 出错的却被扔了 —— 而排障需要的恰恰是错的那些。尾部采样的规则很朴素:</p>
 * <p>1、失败 / 沉默失败 / 有 ERROR 的 Span,一律留;</p>
 * <p>2、自检可信度低于阈值的,留(它极可能是下一个事故);</p>
 * <p>3、其余成功请求按比例留,用 traceId 的哈希做决定,保证同一个 trace 反复评估结果一致。</p>
 */
public final class TailSampler {

    private final double successRate;
    private final double trustFloor;
    private final AtomicLong kept = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public TailSampler(double successRate, double trustFloor) {
        this.successRate = Math.max(0, Math.min(1, successRate));
        this.trustFloor = trustFloor;
    }

    public boolean evaluate(Trace trace) {
        boolean keep = mustKeep(trace) || sampleByHash(trace.traceId());
        if (keep) {
            kept.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
        return keep;
    }

    public boolean mustKeep(Trace trace) {
        if (trace.outcome() != Outcome.SUCCESS) {
            return true;
        }
        if (trace.trust() < trustFloor) {
            return true;
        }
        for (Span span : trace.spans()) {
            if (span.status() == SpanStatus.ERROR || !span.integrityOk()) {
                return true;
            }
        }
        return false;
    }

    private boolean sampleByHash(String traceId) {
        if (successRate >= 1.0) {
            return true;
        }
        if (successRate <= 0.0) {
            return false;
        }
        // 用 traceId 自身做哈希:同一个 trace 无论评估几次结果都一样,便于复现和回溯
        int hash = Math.abs(traceId.hashCode() * 31 + traceId.length());
        return hash % 10_000 < (int) Math.round(successRate * 10_000);
    }

    public long kept() {
        return kept.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public double keepRatio() {
        long total = kept.get() + dropped.get();
        return total == 0 ? 0 : Math.round(kept.get() * 1000.0 / total) / 1000.0;
    }
}
