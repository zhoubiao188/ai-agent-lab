package com.example.o11y.store;

import com.example.o11y.span.Trace;

import java.util.List;
import java.util.Optional;

/** Trace 存储。Demo 用内存环,线上换 Jaeger / Tempo / ClickHouse,接口不变。 */
public interface TraceStore {

    void save(Trace trace);

    Optional<Trace> find(String traceId);

    /** 按开始时间倒序返回最近若干条。 */
    List<Trace> recent(int limit);

    List<Trace> all();

    int size();

    void clear();
}
