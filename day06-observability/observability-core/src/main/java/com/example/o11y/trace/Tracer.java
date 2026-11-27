package com.example.o11y.trace;

import com.example.o11y.attr.AttrKeys;
import com.example.o11y.clock.Clock;
import com.example.o11y.decision.Decision;
import com.example.o11y.decision.DecisionPoint;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.IntegrityGrade;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanEvent;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.store.TraceStore;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 追踪器:创建 Span、维护父子关系、结束时交给采样器与存储。
 *
 * <p>为什么不用 MDC 那一套:MDC 是线程绑定的字符串,子线程不会自动继承,线程池复用还会串味;
 * 而 Agent 的并行步骤恰恰是"父 Span 在这个线程、子 Span 在那个线程"。这里的做法是把父 Span
 * 对象显式带过去({@link Carrier} + {@link #resume}),并发链路上父子关系不依赖任何隐式状态。</p>
 */
public final class Tracer {

    private final Clock clock;
    private final Redactor redactor;
    private final TailSampler sampler;
    private final TraceStore store;

    private final ThreadLocal<Deque<Span>> stack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Trace> current = new ThreadLocal<>();
    /** 未结束的 Trace,供跨线程按 traceId 找回。 */
    private final Map<String, Trace> inFlight = new ConcurrentHashMap<>();

    public Tracer(Clock clock, Redactor redactor, TailSampler sampler, TraceStore store) {
        this.clock = clock;
        this.redactor = redactor;
        this.sampler = sampler;
        this.store = store;
    }

    public Clock clock() {
        return clock;
    }

    public Redactor redactor() {
        return redactor;
    }

    public Trace begin(String taskId, String scenario, String question, String userId) {
        String traceId = newTraceId();
        Span root = new Span(traceId, newSpanId(), null, "run " + taskId, SpanKind.RUN, null, clock.nanoTime());
        Trace trace = new Trace(traceId, taskId, scenario, question, userId, clock.epochMilli(), root);
        current.set(trace);
        stack.get().clear();
        stack.get().push(root);
        inFlight.put(traceId, trace);
        return trace;
    }

    public Optional<Trace> currentTrace() {
        return Optional.ofNullable(current.get());
    }

    public Optional<Span> currentSpan() {
        Deque<Span> deque = stack.get();
        return deque.isEmpty() ? Optional.empty() : Optional.of(deque.peek());
    }

    public Scope span(String name, SpanKind kind) {
        return openSpan(currentSpan().orElse(null), name, kind, null);
    }

    public Scope span(String name, SpanKind kind, String decisionCode) {
        return openSpan(currentSpan().orElse(null), name, kind, decisionCode);
    }

    /**
     * 在指定父 Span 下开子 Span。并行步骤需要它:父 Span 属于主线程,子 Span 属于工作线程。
     */
    public Scope spanUnder(Span parent, String name, SpanKind kind) {
        return openSpan(parent, name, kind, null);
    }

    private Scope openSpan(Span parent, String name, SpanKind kind, String decisionCode) {
        Trace trace = current.get();
        if (trace == null) {
            throw new IllegalStateException("没有进行中的 Trace,请先调用 begin() 或 resume()");
        }
        String parentId = parent == null ? null : parent.spanId();
        Span span = new Span(trace.traceId(), newSpanId(), parentId, name, kind, decisionCode, clock.nanoTime());
        if (parent != null) {
            parent.addChild(span);
        }
        stack.get().push(span);
        return new Scope(this, span, parent);
    }

    /** 从另一个线程接回上下文。返回的 Scope 关闭时自动解绑线程。 */
    public Scope resume(Carrier carrier, String name, SpanKind kind) {
        Trace trace = inFlight.get(carrier.traceId());
        if (trace == null) {
            trace = store.find(carrier.traceId()).orElseThrow(
                    () -> new IllegalStateException("Trace 不存在: " + carrier.traceId()));
        }
        Span parent = trace.span(carrier.parentSpanId()).orElse(trace.root());
        current.set(trace);
        stack.get().clear();
        stack.get().push(parent);
        return openSpan(parent, name, kind, null);
    }

    public Carrier capture() {
        Trace trace = current.get();
        if (trace == null) {
            return null;
        }
        return new Carrier(trace.traceId(), currentSpan().map(Span::spanId).orElse(trace.root().spanId()));
    }

    /** 跨线程传递的最小上下文:只有两个 id,不传业务对象,避免把线程安全责任扩散出去。 */
    public record Carrier(String traceId, String parentSpanId) {
    }

    /**
     * 解绑当前线程。线程池里的工作线程用完必须调用,否则下一次任务会捡到上一个 Trace,
     * 表现为"两个请求的 Span 串到一棵树上"——这种错极难复现,一定要在 finally 里清。
     */
    public void detach() {
        current.remove();
        stack.remove();
    }

    public DecisionScope decision(DecisionPoint point) {
        String name = point.code() + " " + point.title();
        Scope scope = span(name, SpanKind.DECISION, point.code());
        scope.tag(AttrKeys.DECISION_CODE, point.code())
                .tag(AttrKeys.DECISION_TITLE, point.title());
        return new DecisionScope(scope, point);
    }

    /** 结束一次运行:收根 Span、算结论、交给采样器决定留不留。 */
    public Trace finish(Trace trace, Outcome outcome) {
        trace.outcome(outcome);
        Span root = trace.root();
        root.attr(AttrKeys.AGENT_OUTCOME, outcome.name());
        root.end(clock.nanoTime(), outcome == Outcome.SUCCESS ? SpanStatus.OK : SpanStatus.ERROR);
        trace.attr(AttrKeys.AGENT_STEP_COUNT, trace.spans().stream()
                .filter(s -> s.kind() == SpanKind.STEP).count());

        // 采样只决定"要不要上报到远端",不决定"要不要留在本地内存环"。
        // 两者混在一起是很多系统的隐形坑:刚跑完的请求因为没被采样,页面点进去直接 404,
        // 值班的人第一反应是"追踪坏了"。
        boolean sampled = sampler.evaluate(trace);
        trace.sampled(sampled);
        inFlight.remove(trace.traceId());
        store.save(trace);
        current.remove();
        stack.get().clear();
        return trace;
    }

    /** 中止当前 Span,标记为错误。给 finally 里的兜底用。 */
    void closeSpan(Span span, Span parent) {
        span.end(clock.nanoTime(), SpanStatus.OK);
        Deque<Span> deque = stack.get();
        if (!deque.isEmpty() && deque.peek() == span) {
            deque.pop();
        } else {
            deque.remove(span);
        }
        if (parent != null && !deque.isEmpty() && deque.peek() != parent) {
            // 父子栈错位说明有人忘了关 Span;把父压回去保证后续 Span 不会挂错节点
            deque.push(parent);
        }
    }

    private static String newTraceId() {
        return hex(16);
    }

    private static String newSpanId() {
        return hex(8);
    }

    private static String hex(int bytes) {
        StringBuilder sb = new StringBuilder(bytes * 2);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < bytes; i++) {
            sb.append(String.format("%02x", r.nextInt(256)));
        }
        return sb.toString();
    }

    /** 一个 Span 的作用域。try-with-resources 关闭即结束,异常路径不会漏 Span。 */
    public final class Scope implements AutoCloseable {

        private final Tracer tracer;
        private final Span span;
        private final Span parent;
        private int redactions;

        private Scope(Tracer tracer, Span span, Span parent) {
            this.tracer = tracer;
            this.span = span;
            this.parent = parent;
        }

        public Span span() {
            return span;
        }

        public String spanId() {
            return span.spanId();
        }

        /**
         * 写属性。字符串一律过脱敏,命中数累计到 Span 上。
         * 敏感键(token、password)整体替换,不留任何片段。
         */
        public Scope tag(String key, Object value) {
            if (key == null || value == null) {
                return this;
            }
            if (redactor.sensitiveKey(key)) {
                span.attr(key, "***");
                redactions++;
                return this;
            }
            if (value instanceof String text) {
                Redactor.Result r = redactor.redact(text);
                span.attr(key, r.text());
                redactions += r.hits();
            } else {
                span.attr(key, value);
            }
            return this;
        }

        public Scope tags(Map<String, Object> attrs) {
            if (attrs != null) {
                attrs.forEach(this::tag);
            }
            return this;
        }

        public Scope event(String name, Map<String, Object> attrs) {
            SpanEvent e = new SpanEvent(name, clock.epochMilli());
            if (attrs != null) {
                attrs.forEach(e::attr);
            }
            span.event(e);
            return this;
        }

        public Scope event(String name) {
            return event(name, null);
        }

        public Scope error(String kind, String message) {
            span.error(kind, message);
            span.attr(AttrKeys.ERROR_KIND, kind);
            return this;
        }

        public Scope attempts(int attempts) {
            span.attempts(attempts);
            return this;
        }

        public Scope tokens(long in, long out, long costMicros) {
            span.addTokens(in, out, costMicros);
            span.attr(AttrKeys.LLM_TOKENS_IN, in);
            span.attr(AttrKeys.LLM_TOKENS_OUT, out);
            span.attr(AttrKeys.LLM_COST_MICROS, costMicros);
            return this;
        }

        public Scope integrity(boolean ok, String reason) {
            span.integrity(ok, reason);
            return this;
        }

        public Scope integrity(boolean ok, String reason, IntegrityGrade grade) {
            span.integrity(ok, reason, grade);
            return this;
        }

        @Override
        public void close() {
            span.redactions(redactions);
            if (redactions > 0) {
                span.attr(AttrKeys.REDACTION_COUNT, redactions);
            }
            tracer.closeSpan(span, parent);
        }
    }

    /** 决策作用域:绑定 Span,记录台账,顺手把决策要素写进 Span 属性供聚合使用。 */
    public final class DecisionScope implements AutoCloseable {

        private final Scope scope;
        private final DecisionPoint point;

        private DecisionScope(Scope scope, DecisionPoint point) {
            this.scope = scope;
            this.point = point;
        }

        public Scope scope() {
            return scope;
        }

        public String spanId() {
            return scope.spanId();
        }

        public void record(Decision decision) {
            Trace trace = current.get();
            if (trace == null) {
                return;
            }
            Decision bound = decision.toBuilder()
                    .seq(trace.decisions().size() + 1)
                    .spanId(scope.spanId())
                    .build();
            trace.addDecision(bound);
            scope.tag(AttrKeys.DECISION_CHOSEN, bound.chosen())
                    .tag(AttrKeys.DECISION_BASIS, bound.basis().name())
                    .tag(AttrKeys.DECISION_CONFIDENCE, round3(bound.confidence()))
                    .tag(AttrKeys.DECISION_MARGIN, round3(bound.margin()))
                    .tag(AttrKeys.DECISION_ALTERNATIVES, bound.alternatives().size());
            if (bound.confidence() < 0.5 || bound.margin() < 0.1) {
                scope.event("decision.near_tie", Map.of(
                        "confidence", bound.confidence(),
                        "margin", round3(bound.margin())));
            }
        }

        @Override
        public void close() {
            scope.close();
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static String uuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
