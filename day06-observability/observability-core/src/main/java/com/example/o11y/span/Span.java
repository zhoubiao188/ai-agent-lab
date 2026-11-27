package com.example.o11y.span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个 Span = 一次可观测的工作单元。
 *
 * <p>设计上有三个刻意为之的选择:</p>
 * <p>1、属性表是开放的 {@code Map<String,Object>},而不是为每种 Span 建一个类。Agent 的属性
 * 长得太快(今天加 token,明天加 rerank 分数),为每个属性建字段会让内核跟着业务改版;</p>
 * <p>2、children 直接持有对象引用,不用 parentId 回查。自耗时 = 自身区间减去子区间并集,
 * 并行子 Span 重叠的部分不会被重复扣掉(见 {@link #selfNanos()});</p>
 * <p>3、attempts 与 errorKind 是一等字段,不塞进属性表。这两个字段是"嫌疑排序"的输入,
 * 留在 Map 里迟早有人拼错 key。</p>
 */
public final class Span {

    private final String traceId;
    private final String spanId;
    private final String parentId;
    private final String name;
    private final SpanKind kind;
    /** 决策点编码,仅 DECISION 类型有值。 */
    private final String decisionCode;
    private final long startNanos;

    private long endNanos;
    private SpanStatus status = SpanStatus.UNSET;
    private String errorKind;
    private String errorMessage;

    /** 实际尝试次数,从 1 开始。大于 1 说明发生了重试。 */
    private int attempts = 1;
    private long tokensIn;
    private long tokensOut;
    private long costMicros;
    /** 语义完整性信号:写入回显不一致、校验不通过时会置 false。沉默失败只能靠它发现。 */
    private boolean integrityOk = true;
    /**
     * 完整性失败的性质:是坏结果已经落地,还是被护栏拦下了。
     * 默认按"已落地"处理 —— 宁可高估,不要把一个真正的现场当成误报。
     */
    private IntegrityGrade integrityGrade = IntegrityGrade.LANDED;
    private int redactions;

    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<SpanEvent> events = new ArrayList<>();
    // 并行步骤会在两个线程里同时挂子 Span,用写时复制列表避免并发修改异常
    private final List<Span> children = new CopyOnWriteArrayList<>();

    public Span(String traceId, String spanId, String parentId, String name, SpanKind kind,
                String decisionCode, long startNanos) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentId = parentId;
        this.name = name;
        this.kind = kind;
        this.decisionCode = decisionCode;
        this.startNanos = startNanos;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentId() {
        return parentId;
    }

    public String name() {
        return name;
    }

    public SpanKind kind() {
        return kind;
    }

    public String decisionCode() {
        return decisionCode;
    }

    public long startNanos() {
        return startNanos;
    }

    public long endNanos() {
        return endNanos;
    }

    public SpanStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public long tokensIn() {
        return tokensIn;
    }

    public long tokensOut() {
        return tokensOut;
    }

    public long costMicros() {
        return costMicros;
    }

    public boolean integrityOk() {
        return integrityOk;
    }

    public IntegrityGrade integrityGrade() {
        return integrityGrade;
    }

    public int redactions() {
        return redactions;
    }

    public String errorKind() {
        return errorKind;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Map<String, Object> attrs() {
        return Collections.unmodifiableMap(attrs);
    }

    public List<SpanEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public List<Span> children() {
        return Collections.unmodifiableList(children);
    }

    public Object attr(String key) {
        return attrs.get(key);
    }

    public String attrString(String key) {
        Object v = attrs.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public long attrLong(String key, long fallback) {
        Object v = attrs.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    public boolean attrBool(String key, boolean fallback) {
        Object v = attrs.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    // ---- 由 Tracer 写入 ----

    public Span attr(String key, Object value) {
        if (key != null && value != null) {
            attrs.put(key, value);
        }
        return this;
    }

    public Span addTokens(long in, long out, long micros) {
        this.tokensIn += in;
        this.tokensOut += out;
        this.costMicros += micros;
        return this;
    }

    public Span attempts(int value) {
        this.attempts = Math.max(1, value);
        return this;
    }

    public Span integrity(boolean ok, String reason) {
        return integrity(ok, reason, IntegrityGrade.LANDED);
    }

    /**
     * 打完整性标记。{@code grade} 决定这个信号在排障时值多少分:
     * 护栏拦下的只有已落地缺陷的一半 —— 它证明"有个东西不对",但不证明"坏在这里"。
     */
    public Span integrity(boolean ok, String reason, IntegrityGrade grade) {
        this.integrityOk = ok;
        if (!ok) {
            this.integrityGrade = grade == null ? IntegrityGrade.LANDED : grade;
            if (reason != null) {
                attrs.put("integrity.reason", reason);
            }
            attrs.put("integrity.grade", this.integrityGrade.name());
        }
        return this;
    }

    public Span redactions(int count) {
        this.redactions += count;
        return this;
    }

    public Span error(String kind, String message) {
        this.status = SpanStatus.ERROR;
        this.errorKind = kind;
        this.errorMessage = message;
        return this;
    }

    public Span ok() {
        if (status == SpanStatus.UNSET) {
            status = SpanStatus.OK;
        }
        return this;
    }

    public Span event(SpanEvent event) {
        events.add(event);
        return this;
    }

    public void end(long endNanos, SpanStatus fallbackStatus) {
        this.endNanos = Math.max(endNanos, startNanos);
        if (status == SpanStatus.UNSET) {
            status = fallbackStatus;
        }
    }

    /** 挂子 Span。调用方是同包的 Tracer,但并行步骤会跨线程,所以必须是 public。 */
    public void addChild(Span child) {
        children.add(child);
    }

    public long durationNanos() {
        return Math.max(0L, endNanos - startNanos);
    }

    public double durationMs() {
        return durationNanos() / 1_000_000.0;
    }

    /**
     * 自耗时:自身时长减去所有子区间(合并重叠)的长度。
     *
     * <p>并行执行的两个步骤子区间会重叠,如果简单地把子 Span 时长相加再减,
     * 自耗时会被减成负数,父节点反而看起来"不耗时"。这里按区间合并算并集。</p>
     */
    public long selfNanos() {
        long total = durationNanos();
        if (children.isEmpty()) {
            return total;
        }
        List<long[]> intervals = new ArrayList<>(children.size());
        for (Span c : children) {
            long s = Math.max(startNanos, c.startNanos);
            long e = Math.min(endNanos, c.endNanos);
            if (e > s) {
                intervals.add(new long[]{s, e});
            }
        }
        intervals.sort((a, b) -> Long.compare(a[0], b[0]));
        long covered = 0;
        long curStart = -1;
        long curEnd = -1;
        for (long[] iv : intervals) {
            if (curStart < 0) {
                curStart = iv[0];
                curEnd = iv[1];
            } else if (iv[0] <= curEnd) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                covered += curEnd - curStart;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        if (curStart >= 0) {
            covered += curEnd - curStart;
        }
        return Math.max(0L, total - covered);
    }

    public double selfMs() {
        return selfNanos() / 1_000_000.0;
    }

    /** 深度优先展开,先父后子,保证页面上瀑布图的顺序稳定。 */
    public void flatten(List<Span> out) {
        out.add(this);
        for (Span c : children) {
            c.flatten(out);
        }
    }

    @Override
    public String toString() {
        return "Span{" + kind + " '" + name + "' " + spanId + " attempts=" + attempts
                + " status=" + status + " selfMs=" + String.format("%.1f", selfMs()) + "}";
    }
}
