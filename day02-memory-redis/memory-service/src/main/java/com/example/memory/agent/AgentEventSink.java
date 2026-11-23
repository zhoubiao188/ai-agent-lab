package com.example.memory.agent;

/**
 * SSE 事件出口。编排器只依赖这个接口,不关心对面是 SseEmitter、WebSocket 还是测试里的收集器。
 */
public interface AgentEventSink {

    void emit(String event, Object data);

    /** 客户端是否已断开 —— 断开后应尽快停止生成,省下 token */
    boolean closed();
}
