package com.example.agent.agent;

import java.util.Map;

/**
 * Agent 事件输出的抽象。Controller 用 SseEmitter 实现它,
 * 单元测试里换成 List 收集,编排逻辑就能脱离 Web 层被测试。
 */
public interface AgentEventSink {

    /**
     * @param event 事件名:message_start / delta / delta_reset / tool_start / tool_result / message_end / done / error
     * @param data  可被 Jackson 序列化的对象
     */
    void emit(String event, Object data);

    /** 客户端是否已断开 */
    boolean closed();
}
