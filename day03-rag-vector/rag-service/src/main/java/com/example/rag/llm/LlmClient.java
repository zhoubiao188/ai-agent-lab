package com.example.rag.llm;

import com.example.rag.chunk.Chunk;

import java.util.List;
import java.util.function.Consumer;

/** 生成模型抽象。mock 用于离线跑通全链路与单测,openai 兼容任意 /chat/completions 流式接口。 */
public interface LlmClient {

    String name();

    /** 流式产出答案片段。实现必须自己处理超时与重试,不能把异常直接抛到 SSE 线程外。 */
    void streamAnswer(String question, List<Chunk> contexts, Consumer<String> onDelta);
}
