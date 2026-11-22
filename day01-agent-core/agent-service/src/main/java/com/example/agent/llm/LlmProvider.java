package com.example.agent.llm;

import com.example.agent.llm.dto.LlmRequest;

/**
 * LLM 供应方抽象。所有实现都必须是"流式"的:
 * 内容增量、工具调用增量、结束原因都会通过 {@link StreamListener} 回调。
 */
public interface LlmProvider {

    String name();

    /** 是否具备真实推理能力(用于前端提示"当前为离线演示模式")。 */
    default boolean realModel() {
        return true;
    }

    /**
     * 以流式方式发起一次补全。方法返回时代表本次调用已结束(或被取消)。
     */
    void stream(LlmRequest request, StreamListener listener);

    /** 流式回调。实现方需保证回调在调用线程内顺序执行。 */
    interface StreamListener {
        /** 文本增量 */
        void onContentDelta(String delta);

        /**
         * 工具调用增量。OpenAI 协议下同一个工具调用的 name/arguments 会分多帧到达,
         * 由上层按 index 聚合。
         */
        void onToolCallDelta(int index, String id, String name, String argumentsFragment);

        /** 本次调用结束 */
        void onFinish(String finishReason, TokenUsage usage);

        /** 发生错误 */
        void onError(Throwable error);
    }

    record TokenUsage(int promptTokens, int completionTokens) {
        public static TokenUsage unknown() {
            return new TokenUsage(0, 0);
        }

        public int total() {
            return promptTokens + completionTokens;
        }
    }
}
