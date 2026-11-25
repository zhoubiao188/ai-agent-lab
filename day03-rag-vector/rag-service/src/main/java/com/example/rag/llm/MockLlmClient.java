package com.example.rag.llm;

import com.example.rag.chunk.Chunk;

import java.util.List;
import java.util.function.Consumer;

/**
 * 离线生成实现:不带任何模型,把最相关的三个片段的主题句按引用格式拼出来。
 * <p>它的价值在于让整条链路(检索 → 拼提示词 → 流式返回 → 前端渲染引用)
 * 在没有 API Key 的机器上也能完整跑通并被单测覆盖。
 * 换成真模型只需要改一行配置。</p>
 */
public class MockLlmClient implements LlmClient {

    private final long charDelayMillis;

    public MockLlmClient(long charDelayMillis) {
        this.charDelayMillis = charDelayMillis;
    }

    @Override
    public String name() {
        return "mock-llm";
    }

    @Override
    public void streamAnswer(String question, List<Chunk> contexts, Consumer<String> onDelta) {
        String answer = compose(question, contexts);
        // 按 24 个字符一帧吐出,模拟真实模型的增量 token,顺便验证前端的增量渲染
        int step = 24;
        for (int i = 0; i < answer.length(); i += step) {
            String piece = answer.substring(i, Math.min(answer.length(), i + step));
            onDelta.accept(piece);
            if (charDelayMillis > 0) {
                try {
                    Thread.sleep(charDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String compose(String question, List<Chunk> contexts) {
        StringBuilder sb = new StringBuilder();
        if (contexts.isEmpty()) {
            sb.append("资料中没有相关内容。请补充知识库或换个问法。");
            return sb.toString();
        }
        sb.append("根据检索到的资料,可以这样回答「").append(question.trim()).append("」:\n\n");
        int limit = Math.min(3, contexts.size());
        for (int i = 0; i < limit; i++) {
            Chunk c = contexts.get(i);
            sb.append(i + 1).append("、").append(leadSentence(c.text())).append(" [").append(i + 1).append("]\n");
        }
        sb.append("\n以上结论对应的出处:").append('\n');
        for (int i = 0; i < limit; i++) {
            sb.append("[").append(i + 1).append("] ").append(contexts.get(i).citation()).append('\n');
        }
        sb.append("\n(当前为 mock 生成器,用于离线联调;把 rag.llm.provider 设为 openai 并配置 Key 后即为真实模型输出。)");
        return sb.toString();
    }

    /** 取片段的第一句作为主题句。 */
    private String leadSentence(String text) {
        int end = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '!' || c == '?' || c == '！' || c == '？') {
                end = i;
                break;
            }
        }
        String s = end > 0 ? text.substring(0, end + 1) : text;
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }
}
