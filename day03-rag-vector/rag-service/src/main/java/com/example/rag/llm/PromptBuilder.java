package com.example.rag.llm;

import com.example.rag.chunk.Chunk;

import java.util.List;

/**
 * 把检索结果拼成提示词。
 * <p>三个不显眼但决定效果的细节:</p>
 * <p>1、每个片段带编号与出处,并要求模型在句末标注 [n],否则用户无法验证答案;</p>
 * <p>2、明确写"资料里没有就说不知道",否则模型会用先验知识补全,变成幻觉;</p>
 * <p>3、片段之间用分隔符隔开,不要只靠换行 —— 长上下文里模型会跨片段串味。</p>
 */
public final class PromptBuilder {

    public static final String SYSTEM = """
            你是一个严谨的技术文档助手。
            规则:
            1、只依据给定的资料片段回答,不要引入资料之外的事实;
            2、如果资料不足以回答,直接说"资料中没有相关内容",并说明缺少什么;
            3、每个结论后面用 [编号] 标注来源,编号对应资料片段;
            4、回答用中文,先给结论再给依据,不要复述问题。
            """;

    private PromptBuilder() {
    }

    public static String user(String question, List<Chunk> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 资料片段 ==\n");
        for (int i = 0; i < contexts.size(); i++) {
            Chunk c = contexts.get(i);
            sb.append("\n[").append(i + 1).append("] 《").append(c.docTitle())
                    .append(" · ").append(c.section()).append("》\n");
            sb.append(c.text()).append('\n');
            sb.append("---\n");
        }
        sb.append("\n== 问题 ==\n").append(question).append('\n');
        return sb.toString();
    }
}
