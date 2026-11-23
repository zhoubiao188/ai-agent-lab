package com.example.memory.memory;

import com.example.memory.memory.TurnSegmenter.Segment;

import java.util.List;

/**
 * 规则兜底摘要器。
 *
 * <p>摘要模型不可用(超时/限流/欠费/熔断)时,至少保住三件事:</p>
 * <ol>
 *   <li>用户最初的目标 —— 取被折叠轮次里的第一条 user 消息;</li>
 *   <li>关键标识符 —— {@link PinnedFacts} 抽出来的单号/金额/日期;</li>
 *   <li>最后一个未完成的问题 —— 取最后一条 user 消息,通常就是当前诉求。</li>
 * </ol>
 *
 * <p>它写出来的摘要明显比模型差,但它是<b>确定性</b>的:同样的输入永远得到同样的输出,
 * 可以单测,也绝不会在故障时把对话卡住。生产上这类"降级路径"必须提前写好并演练,
 * 否则真出事时你只能在"报错"和"瞎编"之间二选一。</p>
 */
public final class LocalSummarizer {

    private static final int MAX_GOAL_CHARS = 120;
    private static final int MAX_FACT_CHARS = 260;

    private LocalSummarizer() {
    }

    public static String summarize(String oldSummary, List<Segment> folded, List<String> facts) {
        StringBuilder sb = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append(oldSummary.trim()).append("\n\n");
        }

        String goal = "";
        for (Segment s : folded) {
            String t = s.firstUserText();
            if (t != null && !t.isBlank()) {
                goal = t.trim();
                break;
            }
        }
        String lastAsk = "";
        for (int i = folded.size() - 1; i >= 0; i--) {
            String t = folded.get(i).firstUserText();
            if (t != null && !t.isBlank()) {
                lastAsk = t.trim();
                break;
            }
        }

        sb.append("【用户目标】").append(clip(goal, MAX_GOAL_CHARS)).append('\n');
        sb.append("【已确认事实】").append(facts == null || facts.isEmpty()
                ? "无(摘要服务不可用,未能提炼)"
                : clip(String.join(";", facts), MAX_FACT_CHARS)).append('\n');
        sb.append("【已做决策】无\n");
        sb.append("【待办事项】").append(clip(lastAsk, MAX_GOAL_CHARS)).append('\n');
        sb.append("【未解决问题】").append(clip(lastAsk, MAX_GOAL_CHARS)).append('\n');
        sb.append("(注:本轮压缩时摘要模型不可用,以上为规则提取结果)\n");
        return sb.toString().trim();
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return "无";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
