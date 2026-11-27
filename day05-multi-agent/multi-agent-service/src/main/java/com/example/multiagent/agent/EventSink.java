package com.example.multiagent.agent;

/**
 * 事件出口。多线程并发发事件时由实现方保证串行化(SSE 的 send 不是线程安全的)。
 */
@FunctionalInterface
public interface EventSink {

    void emit(AgentEvent event);

    EventSink NOOP = event -> {
    };
}
