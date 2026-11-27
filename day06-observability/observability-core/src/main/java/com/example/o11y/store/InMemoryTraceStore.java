package com.example.o11y.store;

import com.example.o11y.span.Trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存环形存储,给 Demo 和单机排障用。
 *
 * <p>两个细节:</p>
 * <p>1、按 traceId 建索引 + 一个双端队列维持时间序。只用一个队列按 id 找是 O(n),
 * 排障页每次点开都要扫全表,百来条就明显卡;</p>
 * <p>2、容量到顶从队尾淘汰最老的。**淘汰时必须同步删索引**,否则 Map 只增不减,
 * 长跑一天就 OOM —— 这是内存追踪实现里最常见的一个泄漏点。</p>
 */
public final class InMemoryTraceStore implements TraceStore {

    private final int capacity;
    private final Map<String, Trace> byId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Trace> order = new ConcurrentLinkedDeque<>();

    public InMemoryTraceStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void save(Trace trace) {
        Trace previous = byId.put(trace.traceId(), trace);
        if (previous != null) {
            order.remove(previous);
        }
        order.addFirst(trace);
        while (order.size() > capacity) {
            Trace evicted = order.pollLast();
            if (evicted == null) {
                break;
            }
            byId.remove(evicted.traceId(), evicted);
        }
    }

    @Override
    public Optional<Trace> find(String traceId) {
        return Optional.ofNullable(byId.get(traceId));
    }

    @Override
    public List<Trace> recent(int limit) {
        List<Trace> out = new ArrayList<>(Math.min(limit, order.size()));
        for (Trace t : order) {
            if (out.size() >= limit) {
                break;
            }
            out.add(t);
        }
        return out;
    }

    @Override
    public List<Trace> all() {
        return new ArrayList<>(order);
    }

    @Override
    public int size() {
        return byId.size();
    }

    @Override
    public synchronized void clear() {
        byId.clear();
        order.clear();
    }

    public int capacity() {
        return capacity;
    }
}
