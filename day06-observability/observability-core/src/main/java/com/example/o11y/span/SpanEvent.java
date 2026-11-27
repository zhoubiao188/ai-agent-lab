package com.example.o11y.span;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Span 内的离散事件(带时间点的注释),比如"第 1 次重试前退避 80ms""命中熔断"。
 *
 * <p>为什么不用普通日志:日志没有时间轴归属,一个请求里打 40 行 log,你无法把它们按 Span 分组;
 * 事件挂在 Span 上,页面上就自然出现在对应的瀑布条上。</p>
 */
public final class SpanEvent {

    private final String name;
    private final long epochMilli;
    private final Map<String, Object> attrs = new LinkedHashMap<>();

    public SpanEvent(String name, long epochMilli) {
        this.name = name;
        this.epochMilli = epochMilli;
    }

    public SpanEvent attr(String key, Object value) {
        if (key != null && value != null) {
            attrs.put(key, value);
        }
        return this;
    }

    public String name() {
        return name;
    }

    public long epochMilli() {
        return epochMilli;
    }

    public Map<String, Object> attrs() {
        return attrs;
    }
}
