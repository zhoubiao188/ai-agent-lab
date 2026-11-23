package com.example.memory.memory;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.llm.LlmProvider;
import com.example.memory.memory.TurnSegmenter.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 滚动摘要压缩器 —— 本项目的核心。
 *
 * <p>解决的问题:一个 50 轮的客服会话,原始消息序列轻松超过 2 万 token。
 * 全量回放 → 贵、慢、且超过窗口直接报错;简单滑窗裁剪 → 用户在第 3 轮说过的订单号,
 * 到第 20 轮就"忘了",体验断崖式下跌。</p>
 *
 * <p>做法是<b>增量滚动摘要</b>:不重新总结全部历史,而是把「已有摘要」和「本轮要丢弃的对话」
 * 合并成一份新摘要,复杂度恒定。一次压缩的输入 ≈ 旧摘要(700 字)+ 被丢弃的几轮对话,
 * 输出 ≈ 700 字,和会话总长度无关 —— 这是它能撑住 50 轮甚至 500 轮的原因。</p>
 *
 * <p>四条硬性设计:</p>
 * <ol>
 *   <li><b>只按整轮切</b>:见 {@link TurnSegmenter},永不拆散 tool_calls/tool 对;</li>
 *   <li><b>摘要 + 关键事实双轨</b>:摘要允许模糊,数字和单号由 {@link PinnedFacts} 保真;</li>
 *   <li><b>失败必须降级</b>:摘要模型挂了就退化为本地规则摘要,宁可信息少,不可阻塞对话;</li>
 *   <li><b>熔断</b>:连续失败 N 次后一段时间内直接走本地摘要,不再给慢模型拖死主链路的机会。</li>
 * </ol>
 */
@Component
public class SummaryCompactor {

    private static final Logger log = LoggerFactory.getLogger(SummaryCompactor.class);

    /** mock provider 靠这个标记识别"这是摘要任务,不是普通对话" */
    public static final String SUMMARY_TASK_MARKER = "【会话记忆压缩任务】";

    private static final String SUMMARY_SYSTEM = SUMMARY_TASK_MARKER + """

            你是一个会话记忆压缩器,负责把长对话压缩成可供后续轮次继续使用的摘要。
            你的输出会被直接拼进下一轮对话的 system 消息,所以必须遵守:

            1. 只输出摘要正文。不要解释、不要客套、不要代码块围栏、不要"好的"开头。
            2. 严格保留所有数字、单号、ID、金额、日期、时间、人名、地名、产品型号的原文,
               不得改写、不得四舍五入、不得省略单位。
            3. 按下面的固定小节输出,没有内容就写"无":
               【用户目标】
               【已确认事实】
               【已做决策】
               【待办事项】
               【未解决问题】
            4. 新信息与旧摘要冲突时,以新信息为准,并在该条后标注"(已更新)"。
            5. 总长度不超过 600 字。不要编造对话中不存在的信息。
            6. 不要写"用户询问了……助手回答了……"这类流水账,只写结论与状态。
            """;

    /** 单条消息进摘要前截断长度:工具返回的原始 JSON 对"记住用户意图"没有价值 */
    private static final int DIGEST_MAX_CHARS_PER_MSG = 400;
    /** 一次压缩送给模型的对话文本上限 */
    private static final int DIGEST_MAX_TOTAL_CHARS = 4000;

    private final LlmProvider provider;
    private final LlmCaller caller;
    private final AgentProperties props;

    /** 熔断状态:连续失败次数 + 打开截止时间 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long breakerOpenUntil;

    public SummaryCompactor(LlmProvider provider, LlmCaller caller, AgentProperties props) {
        this.provider = provider;
        this.caller = caller;
        this.props = props;
    }

    /**
     * 一次压缩的结果。{@code folded} 是本次被折叠掉的消息,调用方负责写进归档(供后续召回)。
     */
    public record Result(boolean compacted,
                         boolean degraded,
                         String mode,
                         int beforeTokens,
                         int afterTokens,
                         int foldedMessages,
                         long coveredSeq,
                         String summary,
                         List<Msg> folded) {

        public static Result skipped(int tokens) {
            return skipped(tokens, "below_watermark");
        }

        public static Result skipped(int tokens, String reason) {
            return new Result(false, false, "skipped:" + reason, tokens, tokens, 0, 0, "", List.of());
        }
    }

    public boolean breakerOpen() {
        return System.currentTimeMillis() < breakerOpenUntil;
    }

    /**
     * 判断是否需要压缩,需要就压。
     *
     * @param force 手动压缩接口会传 true,忽略水位线
     */
    public Result maybeCompact(Conversation conv, boolean force) {
        AgentProperties.Memory cfg = props.getMemory();
        int before = TokenEstimator.estimateMemory(conv);
        List<Segment> segments = TurnSegmenter.segment(conv.getMessages());

        boolean overWatermark = before > cfg.getBudgetTokens() * cfg.getCompactThreshold()
                || conv.getMessages().size() > cfg.getMaxMessages();
        if (!force && !overWatermark) {
            return Result.skipped(before);
        }
        // 轮次太少时压缩收益为负(摘要调用本身要花几百 token + 一次往返)
        if (segments.size() <= cfg.getKeepMinSegments()) {
            return Result.skipped(before, "too_few_segments");
        }

        int cut = chooseCut(segments, cfg);
        List<Segment> foldedSegments = segments.subList(0, cut);
        List<Msg> foldedMsgs = new ArrayList<>();
        for (Segment s : foldedSegments) {
            foldedMsgs.addAll(s.messages());
        }
        List<String> facts = PinnedFacts.extract(foldedMsgs);
        long coveredSeq = foldedSegments.get(foldedSegments.size() - 1).endSeq();

        String mode = "llm";
        boolean degraded = false;
        String newSummary;
        if (breakerOpen()) {
            newSummary = LocalSummarizer.summarize(conv.getSummary(), foldedSegments, facts);
            degraded = true;
            mode = "local-breaker";
            log.warn("摘要熔断中,会话 {} 使用本地兜底摘要(折叠 {} 条消息)", conv.getId(), foldedMsgs.size());
        } else {
            try {
                newSummary = caller.complete(provider, SUMMARY_SYSTEM,
                        buildUserPrompt(conv.getSummary(), foldedSegments),
                        0.1, cfg.getSummaryTimeout());
                onSummarizerSuccess();
            } catch (Exception e) {
                onSummarizerFailure();
                degraded = true;
                mode = "local-fallback";
                newSummary = LocalSummarizer.summarize(conv.getSummary(), foldedSegments, facts);
                log.warn("摘要失败,降级为本地摘要 cid={} cause={}", conv.getId(), e.getMessage());
            }
        }

        newSummary = sanitize(newSummary, cfg.getSummaryMaxChars());
        if (newSummary.isBlank()) {
            // 极端情况:连兜底摘要都是空的,那就不要动状态,避免"压缩了但什么都没记住"
            return Result.skipped(before, "empty_summary");
        }

        // 收益校验:压缩不是无脑做。如果"新摘要 + 保留窗口"并不比现在小,那这次压缩
        // 纯粹是白花一次模型调用,还会让摘要多漂移一次 —— 直接回滚,保持原状态。
        int keptTokens = conv.getMessages().stream()
                .filter(m -> m.seq() > coveredSeq)
                .mapToInt(TokenEstimator::estimate)
                .sum();
        int projected = TokenEstimator.estimate(newSummary) + keptTokens;
        if (!force && projected >= before) {
            log.debug("会话 {} 压缩收益为负({} -> {}),跳过", conv.getId(), before, projected);
            return Result.skipped(before, "not_profitable");
        }

        conv.setSummary(newSummary);
        conv.setSummaryCoveredSeq(coveredSeq);
        conv.mergePinnedFacts(facts);
        int dropped = conv.dropCovered();

        int after = TokenEstimator.estimateMemory(conv);
        Conversation.Stats stats = conv.getStats();
        stats.setCompactRuns(stats.getCompactRuns() + 1);
        stats.setArchivedMessages(stats.getArchivedMessages() + foldedMsgs.size());
        stats.setTokensSavedEstimate(stats.getTokensSavedEstimate() + Math.max(0, before - after));
        if (degraded) {
            stats.setSummarizerFailures(stats.getSummarizerFailures() + 1);
        }
        conv.touch();

        log.info("会话 {} 压缩完成: {} -> {} token,折叠 {} 条消息(丢弃 {} 条),mode={}",
                conv.getId(), before, after, foldedMsgs.size(), dropped, mode);
        return new Result(true, degraded, mode, before, after, foldedMsgs.size(), coveredSeq, newSummary, foldedMsgs);
    }

    /**
     * 从尾部往前累加,决定保留哪几轮。
     * 两个条件同时满足才停:<b>轮数够</b> 且 <b>token 够</b>。
     */
    private int chooseCut(List<Segment> segments, AgentProperties.Memory cfg) {
        int cut = segments.size();
        int keptSegments = 0;
        int keptTokens = 0;
        while (cut > 1) {
            boolean needMore = keptSegments < cfg.getKeepMinSegments()
                    || keptTokens < cfg.getKeepTailTokens();
            if (!needMore) {
                break;
            }
            Segment tail = segments.get(cut - 1);
            keptTokens += tail.tokens();
            keptSegments++;
            cut--;
        }
        return cut;
    }

    private String buildUserPrompt(String oldSummary, List<Segment> folded) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("【已有摘要】\n");
        sb.append(oldSummary == null || oldSummary.isBlank() ? "(无,这是第一次压缩)" : oldSummary.trim());
        sb.append("\n\n【本次要合并进摘要的对话】\n");

        int budget = DIGEST_MAX_TOTAL_CHARS;
        for (Segment s : folded) {
            if (budget <= 0) {
                sb.append("(更早的轮次已省略)\n");
                break;
            }
            String digest = s.digest(DIGEST_MAX_CHARS_PER_MSG);
            if (digest.length() > budget) {
                digest = digest.substring(0, budget) + "…\n";
            }
            sb.append(digest);
            budget -= digest.length();
        }
        sb.append("\n请输出合并后的新摘要。");
        return sb.toString();
    }

    /** 去掉模型偶尔加的代码围栏/"摘要:"前缀,并按句子边界截断到上限。 */
    static String sanitize(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if (s.startsWith("摘要:") || s.startsWith("摘要：")) {
            s = s.substring(3).trim();
        }
        if (s.length() <= maxChars) {
            return s;
        }
        int cut = maxChars;
        for (int i = maxChars; i > maxChars - 120 && i > 0; i--) {
            char c = s.charAt(i - 1);
            if (c == '。' || c == '\n' || c == ';' || c == '；') {
                cut = i;
                break;
            }
        }
        return s.substring(0, cut).trim() + "…";
    }

    private void onSummarizerSuccess() {
        consecutiveFailures.set(0);
        breakerOpenUntil = 0;
    }

    private void onSummarizerFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        AgentProperties.Memory cfg = props.getMemory();
        if (failures >= cfg.getBreakerFailures()) {
            breakerOpenUntil = System.currentTimeMillis() + cfg.getBreakerCooldown().toMillis();
            log.error("摘要模型连续失败 {} 次,熔断 {} 分钟", failures, cfg.getBreakerCooldown().toMinutes());
            consecutiveFailures.set(0);
        }
    }

    int failureCount() {
        return consecutiveFailures.get();
    }
}
