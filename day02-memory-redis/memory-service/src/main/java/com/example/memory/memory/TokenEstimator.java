package com.example.memory.memory;

import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;

import java.util.List;

/**
 * token 估算器。
 *
 * <p>为什么不用 tiktoken-java 之类的精确分词器:</p>
 * <ul>
 *   <li>我们要的是<b>决策阈值</b>(该不该压缩),不是计费数字,±15% 的误差完全可以接受;</li>
 *   <li>不同厂商分词器不同(DeepSeek / Qwen / GPT 各一套),精确算一个反而给了虚假的安全感;</li>
 *   <li>每次请求都跑一遍 BPE 分词,对 6k token 的上下文是毫秒级开销,但乘以 QPS 就不划算了。</li>
 * </ul>
 *
 * <p>实测校准(以 DeepSeek / Qwen 的中文文本为例):一个汉字 ≈ 0.6~1.2 token,
 * 一个英文单词 ≈ 1.3 token,折中取 <b>汉字 1 token、其他字符 3.6 字符/token</b>。
 * 真正接入生产后,应当拿响应里的 {@code usage.prompt_tokens} 反算一个校准系数,
 * 用 EMA 平滑后乘到估算值上 —— 这一步留给 Day 05 的成本看板一起做。</p>
 */
public final class TokenEstimator {

    /** 中日韩统一表意文字按 1 字 ≈ 1 token 计 */
    private static final double CJK_PER_TOKEN = 1.0;
    /** 其余字符(英文、数字、符号)按 3.6 字符 ≈ 1 token */
    private static final double LATIN_CHARS_PER_TOKEN = 3.6;
    /** 每条消息的角色、分隔符等固定开销 */
    private static final int PER_MESSAGE_OVERHEAD = 4;
    /** 一次工具调用声明(JSON)的额外开销 */
    private static final int PER_TOOL_CALL_OVERHEAD = 14;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(cjk / CJK_PER_TOKEN + other / LATIN_CHARS_PER_TOKEN);
    }

    public static int estimate(Msg msg) {
        if (msg == null) {
            return 0;
        }
        int tokens = PER_MESSAGE_OVERHEAD + estimate(msg.content());
        if (msg.hasToolCalls()) {
            tokens += msg.toolCalls().size() * PER_TOOL_CALL_OVERHEAD;
            for (var call : msg.toolCalls()) {
                tokens += estimate(call.arguments()) + estimate(call.name());
            }
        }
        return tokens;
    }

    public static int estimate(List<Msg> messages) {
        int sum = 0;
        if (messages != null) {
            for (Msg m : messages) {
                sum += estimate(m);
            }
        }
        return sum;
    }

    /**
     * 一次请求真正要付的"记忆成本":摘要 + 活跃窗口。
     * 注意这里不含 system prompt 与工具声明 —— 它们是常量,不该影响"该不该压缩"的判断。
     */
    public static int estimateMemory(Conversation conversation) {
        return estimate(conversation.getSummary()) + estimate(conversation.getMessages());
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // 基本汉字
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x3040 && cp <= 0x30FF)  // 日文假名
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // 韩文
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容汉字
                || (cp >= 0x3000 && cp <= 0x303F); // 中文标点
    }
}
