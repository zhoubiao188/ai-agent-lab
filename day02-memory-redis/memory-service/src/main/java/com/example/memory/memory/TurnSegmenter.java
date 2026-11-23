package com.example.memory.memory;

import com.example.memory.domain.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * 把消息序列切成"可独立删除的最小单元"。
 *
 * <p><b>这是整个记忆模块最容易写错的地方。</b>绝大多数"压缩后突然 400"的线上事故,
 * 根因都是压缩时按"消息条数"裁剪,结果把这一对拆散了:</p>
 *
 * <pre>
 * assistant(tool_calls=[call_1])   ← 被删了
 * tool(tool_call_id=call_1)        ← 留下了
 * </pre>
 *
 * <p>OpenAI 兼容网关对这种序列的报错是 {@code messages with role 'tool' must be a response to
 * a preceding message with 'tool_calls'},而且不会告诉你具体是哪一条。</p>
 *
 * <p>解法:以 <b>user 消息为切点</b>把会话切成若干"轮"(Segment),一轮内包含该 user 消息之后
 * 所有的 assistant / tool 消息。删除只以轮为单位进行,天然保证:</p>
 * <ul>
 *   <li>不会出现孤立的 tool 消息(它永远跟着自己的 assistant 一起被删);</li>
 *   <li>不会出现"只有问题没有答案"的悬空 user 消息;</li>
 *   <li>压缩后的第一条消息一定是 user,序列合法性一眼可验。</li>
 * </ul>
 *
 * <p>代价是一轮可能很大(比如一次并行调 5 个工具),所以要配合 {@link #segment} 之后的
 * "至少保留 N 轮"策略,而不是死抠 token 数。</p>
 */
public final class TurnSegmenter {

    private TurnSegmenter() {
    }

    /**
     * 一轮对话:从一条 user 消息开始,到下一轮 user 消息之前的全部消息。
     * 若会话以非 user 消息开头(理论上不该发生,但历史数据迁移时会出现),
     * 这些"孤儿消息"会自成一轮,以便被优先清掉。
     */
    public record Segment(long startSeq, long endSeq, List<Msg> messages, int tokens) {

        public boolean containsUser() {
            return messages.stream().anyMatch(Msg::isUser);
        }

        /** 第一句人话,用于摘要兜底与前端预览 */
        public String firstUserText() {
            return messages.stream()
                    .filter(Msg::isUser)
                    .map(Msg::content)
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .orElse("");
        }

        /**
         * 把一轮渲染成摘要模型的输入。单条消息截断到 400 字:
         * 工具返回的原始 JSON 可能上千字,但它对"记住用户意图"几乎没有价值。
         */
        public String digest(int maxCharsPerMsg) {
            StringBuilder sb = new StringBuilder();
            for (Msg m : messages) {
                String text = m.readable();
                if (text.isBlank()) {
                    continue;
                }
                if (text.length() > maxCharsPerMsg) {
                    text = text.substring(0, maxCharsPerMsg) + "…(已截断)";
                }
                sb.append(switch (m.role()) {
                    case Msg.ROLE_USER -> "用户: ";
                    case Msg.ROLE_ASSISTANT -> "助手: ";
                    case Msg.ROLE_TOOL -> "工具: ";
                    default -> m.role() + ": ";
                }).append(text).append('\n');
            }
            return sb.toString();
        }
    }

    public static List<Segment> segment(List<Msg> messages) {
        List<Segment> segments = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return segments;
        }
        List<Msg> current = new ArrayList<>();
        for (Msg m : messages) {
            if (m.isUser() && !current.isEmpty()) {
                segments.add(toSegment(current));
                current = new ArrayList<>();
            }
            current.add(m);
        }
        if (!current.isEmpty()) {
            segments.add(toSegment(current));
        }
        return segments;
    }

    private static Segment toSegment(List<Msg> msgs) {
        long start = msgs.get(0).seq();
        long end = msgs.get(msgs.size() - 1).seq();
        return new Segment(start, end, List.copyOf(msgs), TokenEstimator.estimate(msgs));
    }
}
