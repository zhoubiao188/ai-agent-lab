package com.example.memory.llm;

import com.example.memory.domain.Msg;
import com.example.memory.llm.dto.LlmRequest;
import com.example.memory.memory.PinnedFacts;
import com.example.memory.memory.SummaryCompactor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线 mock provider —— 不用任何 API Key 就能跑通整条记忆链路。
 *
 * <p>它要扮演两种角色:</p>
 * <ol>
 *   <li><b>普通对话</b>:按关键词触发工具,回答里带上"第 N 轮"便于观察上下文是否正确;</li>
 *   <li><b>摘要模型</b>:当 system 里出现 {@link SummaryCompactor#SUMMARY_TASK_MARKER} 时,
 *       把输入里的关键事实抽出来,按固定小节拼成摘要。这让我们可以在单元测试里断言
 *       "压缩后订单号还在",而不依赖真实模型的不确定性。</li>
 * </ol>
 *
 * <p>工具参数故意拆成三段下发,顺带验证编排器的增量聚合逻辑。</p>
 */
public class MockLlmProvider implements LlmProvider {

    private final ObjectMapper mapper;

    public MockLlmProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean realModel() {
        return false;
    }

    @Override
    public void stream(LlmRequest request, StreamListener listener) {
        List<Msg> messages = request.messages();

        String systemText = messages.stream()
                .filter(m -> Msg.ROLE_SYSTEM.equals(m.role()))
                .map(Msg::content)
                .filter(c -> c != null)
                .reduce("", (a, b) -> a + b);
        if (systemText.contains(SummaryCompactor.SUMMARY_TASK_MARKER)) {
            String prompt = messages.get(messages.size() - 1).content();
            streamText(listener, summarize(prompt));
            listener.onFinish("stop", new TokenUsage(estimateTokens(request), 120));
            return;
        }

        Msg last = messages.get(messages.size() - 1);
        if (Msg.ROLE_TOOL.equals(last.role())) {
            List<Msg> trailing = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0 && Msg.ROLE_TOOL.equals(messages.get(i).role()); i--) {
                trailing.add(0, messages.get(i));
            }
            StringBuilder sb = new StringBuilder("工具调用完成:\n");
            for (Msg m : trailing) {
                String content = m.content() == null ? "" : m.content();
                sb.append("- **").append(m.name()).append("**: ")
                        .append(content.length() > 120 ? content.substring(0, 120) + "…" : content)
                        .append('\n');
            }
            streamText(listener, sb.toString());
            listener.onFinish("stop", new TokenUsage(estimateTokens(request), 80));
            return;
        }

        String userText = lastUserText(messages);

        if (userText.contains("工单")) {
            String args = "{\"summary\":" + quote(clip(userText, 40)) + ",\"priority\":\"normal\"}";
            int a = args.length() / 3;
            int b = args.length() * 2 / 3;
            listener.onToolCallDelta(0, "call_mock_1", "create_support_ticket", null);
            listener.onToolCallDelta(0, null, null, args.substring(0, a));
            listener.onToolCallDelta(0, null, null, args.substring(a, b));
            listener.onToolCallDelta(0, null, null, args.substring(b));
            listener.onFinish("tool_calls", new TokenUsage(estimateTokens(request), 40));
            return;
        }

        streamText(listener, "收到:**" + clip(userText, 60) + "**\n\n"
                + "我已经结合上下文里的【长期记忆·会话摘要】与【关键信息】继续跟进。"
                + "(当前为离线演示模式,配置 `agent.llm.api-key` 即可换成真实模型)");
        listener.onFinish("stop", new TokenUsage(estimateTokens(request), 60));
    }

    /**
     * 模拟"合格的摘要模型":保留数字与单号、按固定小节输出、不复述流水账。
     */
    private String summarize(String prompt) {
        List<String> facts = PinnedFacts.extract(prompt);
        String goal = firstAfter(prompt, "用户: ");
        String lastAsk = lastAfter(prompt, "用户: ");
        int turns = countOccurrences(prompt, "用户: ");

        StringBuilder sb = new StringBuilder();
        sb.append("【用户目标】").append(goal.isBlank() ? "继续之前的咨询" : clip(goal, 90)).append('\n');
        sb.append("【已确认事实】").append(facts.isEmpty() ? "无" : String.join(";", facts)).append('\n');
        sb.append("【已做决策】按用户确认的方案继续处理,不再重复询问已知信息\n");
        sb.append("【待办事项】跟进最近一轮诉求:").append(clip(lastAsk, 80)).append('\n');
        sb.append("【未解决问题】").append(clip(lastAsk, 80)).append('\n');
        sb.append("(本次合并 ").append(turns).append(" 轮对话)");
        return sb.toString();
    }

    private String firstAfter(String text, String marker) {
        int i = text.indexOf(marker);
        if (i < 0) {
            return "";
        }
        return lineAt(text, i + marker.length());
    }

    private String lastAfter(String text, String marker) {
        int i = text.lastIndexOf(marker);
        if (i < 0) {
            return "";
        }
        return lineAt(text, i + marker.length());
    }

    private String lineAt(String text, int from) {
        int end = text.indexOf('\n', from);
        return (end < 0 ? text.substring(from) : text.substring(from, end)).trim();
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            count++;
            i += needle.length();
        }
        return count;
    }

    private void streamText(StreamListener listener, String text) {
        // 小步下发,让前端的打字机效果有东西可看;不 sleep,避免拖慢测试
        int step = 12;
        for (int i = 0; i < text.length(); i += step) {
            listener.onContentDelta(text.substring(i, Math.min(text.length(), i + step)));
        }
    }

    private String lastUserText(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) {
                return messages.get(i).content() == null ? "" : messages.get(i).content();
            }
        }
        return "";
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private int estimateTokens(LlmRequest request) {
        int chars = 0;
        for (Msg m : request.messages()) {
            chars += m.content() == null ? 0 : m.content().length();
        }
        return chars / 2;
    }
}
