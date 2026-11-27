package com.example.o11y;

import com.example.o11y.clock.VirtualClock;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.store.TraceStore;
import com.example.o11y.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracerTest {

    private Tracer tracer(VirtualClock clock, TraceStore store, double rate) {
        return new Tracer(clock, new Redactor(), new TailSampler(rate, 0.75), store);
    }

    @Test
    @DisplayName("嵌套 Span 的父子关系与自耗时:并行子区间不重复扣减")
    void selfTimeHandlesOverlap() {
        VirtualClock clock = new VirtualClock();
        Tracer tracer = tracer(clock, new InMemoryTraceStore(8), 1.0);
        Trace trace = tracer.begin("t1", "NORMAL", "q", "u");

        Span parent;
        try (Tracer.Scope outer = tracer.span("outer", SpanKind.STEP)) {
            parent = outer.span();
            clock.sleep(100);
            try (Tracer.Scope a = tracer.span("a", SpanKind.TOOL)) {
                clock.sleep(40);
                assertThat(a.span().parentId()).isEqualTo(parent.spanId());
            }
            try (Tracer.Scope b = tracer.span("b", SpanKind.TOOL)) {
                clock.sleep(30);
            }
        }
        tracer.finish(trace, Outcome.SUCCESS);

        assertThat(parent.durationMs()).isEqualTo(170.0);
        // 子 Span 一共 70ms,自耗时 100ms;若天真地把子 Span 相加,这里会算成 100
        assertThat(parent.selfMs()).isEqualTo(100.0);
        assertThat(parent.children()).hasSize(2);
    }

    @Test
    @DisplayName("属性脱敏:手机号、身份证、邮箱、Key 都被替换,命中数被记录")
    void redactsAttributes() {
        VirtualClock clock = new VirtualClock();
        InMemoryTraceStore store = new InMemoryTraceStore(8);
        Tracer tracer = tracer(clock, store, 1.0);
        Trace trace = tracer.begin("t2", "NORMAL", "q", "u");

        try (Tracer.Scope s = tracer.span("tool.user.get", SpanKind.TOOL)) {
            s.tag("user.phone", "手机号 13812345678")
                    .tag("user.idCard", "310101199001011234")
                    .tag("user.email", "zhangsan@example.com")
                    .tag("user.apiKey", "sk-abcdefghijklmnop")
                    .tag("user.password", "p@ssw0rd");
        }
        tracer.finish(trace, Outcome.SUCCESS);

        Span span = store.find(trace.traceId()).orElseThrow().spans().get(1);
        assertThat(span.attrString("user.phone")).isEqualTo("手机号 138****5678");
        assertThat(span.attrString("user.idCard")).isEqualTo("310101********1234");
        assertThat(span.attrString("user.email")).isEqualTo("z***@example.com");
        assertThat(span.attrString("user.apiKey")).contains("***");
        // 敏感键整体替换,不留任何片段
        assertThat(span.attrString("user.password")).isEqualTo("***");
        assertThat(span.redactions()).isEqualTo(5);
    }

    @Test
    @DisplayName("尾部采样:成功请求按比例丢,带错误/低可信度的一律留")
    void tailSamplingKeepsFailures() {
        VirtualClock clock = new VirtualClock();
        TraceStore store = new InMemoryTraceStore(64);
        Tracer tracer = tracer(clock, store, 0.0);

        Trace ok = tracer.begin("ok-1", "NORMAL", "q", "u");
        try (Tracer.Scope s = tracer.span("fine", SpanKind.TOOL)) {
            s.tag("k", "v");
        }
        tracer.finish(ok, Outcome.SUCCESS);

        Trace failed = tracer.begin("bad-1", "SLOW_TOOL", "q", "u");
        try (Tracer.Scope s = tracer.span("boom", SpanKind.TOOL)) {
            s.error("TIMEOUT", "超时");
        }
        tracer.finish(failed, Outcome.FAILED);

        Trace lowTrust = tracer.begin("low-1", "NORMAL", "q", "u");
        lowTrust.trust(0.3);
        tracer.finish(lowTrust, Outcome.SUCCESS);

        // 三条都在内存里(供页面查),但成功那条被标记为"不上报"
        assertThat(store.find(ok.traceId())).isPresent();
        assertThat(store.find(ok.traceId()).orElseThrow().sampled()).isFalse();
        assertThat(store.find(failed.traceId()).orElseThrow().sampled()).isTrue();
        assertThat(store.find(lowTrust.traceId()).orElseThrow().sampled()).isTrue();
    }

    @Test
    @DisplayName("Span 未显式标错时按 OK 结束;显式 error 后不被覆盖")
    void statusDefaults() {
        VirtualClock clock = new VirtualClock();
        Tracer tracer = tracer(clock, new InMemoryTraceStore(8), 1.0);
        Trace trace = tracer.begin("t3", "NORMAL", "q", "u");

        Span plain;
        Span bad;
        try (Tracer.Scope s = tracer.span("plain", SpanKind.INTERNAL)) {
            plain = s.span();
        }
        try (Tracer.Scope s = tracer.span("bad", SpanKind.INTERNAL)) {
            bad = s.span();
            s.error("BUSINESS", "拒绝");
        }
        tracer.finish(trace, Outcome.SUCCESS);

        assertThat(plain.status()).isEqualTo(SpanStatus.OK);
        assertThat(bad.status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("跨线程恢复上下文:工作线程的 Span 挂在指定父节点下")
    void resumeInWorkerThread() throws Exception {
        VirtualClock clock = new VirtualClock();
        InMemoryTraceStore store = new InMemoryTraceStore(8);
        Tracer tracer = tracer(clock, store, 1.0);
        Trace trace = tracer.begin("t4", "NORMAL", "q", "u");

        Tracer.Carrier carrier = tracer.capture();
        Thread worker = new Thread(() -> {
            try (Tracer.Scope child = tracer.resume(carrier, "worker", SpanKind.STEP)) {
                clock.sleep(20);
                assertThat(child.span().parentId()).isEqualTo(trace.root().spanId());
            } finally {
                tracer.detach();
            }
        });
        worker.start();
        worker.join();
        tracer.finish(trace, Outcome.SUCCESS);

        Trace saved = store.find(trace.traceId()).orElseThrow();
        assertThat(saved.spans()).extracting(Span::name).contains("worker");
        assertThat(saved.root().children()).hasSize(1);
    }
}
