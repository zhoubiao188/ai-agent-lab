package com.example.agent.llm;

import com.example.agent.llm.dto.LlmRequest;
import com.example.agent.llm.dto.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线演示 provider:不依赖任何外部 API Key,按关键词脚本化地"扮演"一个会用工具的模型。
 *
 * <p>为什么需要一个 mock:第一,保证 clone 下来 {@code mvn spring-boot:run} 就能跑通全链路,
 * 不用你去注册账号;第二,单元测试里必须有一个确定性的 provider,否则测不了编排逻辑;
 * 第三,它把工具调用帧拆成多段下发,顺便验证了增量聚合的正确性。</p>
 */
public class MockLlmProvider implements LlmProvider {

    private static final Pattern CITY = Pattern.compile("(北京|上海|广州|深圳|杭州|成都)");
    private static final Pattern EXPR = Pattern.compile("[-+*/%().\\d\\s]{3,}");

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
        Msg last = messages.get(messages.size() - 1);

        if ("tool".equals(last.role())) {
            // 一轮里可能并行调了多个工具,末尾会连着多条 tool 消息,全部取出来一起总结
            List<Msg> trailing = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0 && "tool".equals(messages.get(i).role()); i--) {
                trailing.add(0, messages.get(i));
            }
            streamText(listener, composeAnswer(trailing));
            listener.onFinish("stop", new TokenUsage(estimateTokens(request), 80));
            return;
        }

        String userText = lastUserText(messages);
        List<ToolPlan> plans = plan(userText);
        if (plans.isEmpty()) {
            streamText(listener, fallbackAnswer(userText));
            listener.onFinish("stop", new TokenUsage(estimateTokens(request), 60));
            return;
        }

        for (int i = 0; i < plans.size(); i++) {
            ToolPlan p = plans.get(i);
            String args = p.arguments();
            // 故意把参数 JSON 拆成三段下发,模拟真实流式分帧
            int mid1 = args.length() / 3;
            int mid2 = args.length() * 2 / 3;
            listener.onToolCallDelta(i, "call_mock_" + (i + 1), p.tool(), null);
            listener.onToolCallDelta(i, null, null, args.substring(0, mid1));
            listener.onToolCallDelta(i, null, null, args.substring(mid1, mid2));
            listener.onToolCallDelta(i, null, null, args.substring(mid2));
        }
        listener.onFinish("tool_calls", new TokenUsage(estimateTokens(request), 40));
    }

    private record ToolPlan(String tool, String arguments) {
    }

    private List<ToolPlan> plan(String text) {
        List<ToolPlan> plans = new ArrayList<>();
        if (containsAny(text, "天气", "气温", "下雨", "带伞", "热不热", "冷不冷")) {
            Matcher m = CITY.matcher(text);
            String city = m.find() ? m.group(1) : "北京";
            plans.add(new ToolPlan("get_weather", "{\"city\":\"" + city + "\"}"));
        }
        if (containsAny(text, "计算", "等于", "算一下", "帮我算", "算一算", "多少钱", "折合", "百分之")) {
            Matcher m = EXPR.matcher(text);
            while (m.find()) {
                String expr = m.group().trim();
                if (expr.matches(".*\\d.*") && expr.matches(".*[-+*/%].*")) {
                    plans.add(new ToolPlan("calculator", "{\"expression\":\"" + expr + "\"}"));
                    break;
                }
            }
        }
        if (containsAny(text, "退货", "换货", "发票", "会员", "物流", "政策", "规则", "流程", "包邮", "时效")) {
            plans.add(new ToolPlan("search_knowledge_base",
                    "{\"query\":" + quote(text) + ",\"topK\":3}"));
        }
        if (containsAny(text, "几点", "今天几号", "现在时间", "日期")) {
            plans.add(new ToolPlan("get_current_time", "{\"timezone\":\"Asia/Shanghai\"}"));
        }
        if (containsAny(text, "转人工", "投诉", "报障", "工单", "找客服")) {
            plans.add(new ToolPlan("create_support_ticket",
                    "{\"summary\":" + quote(text.length() > 50 ? text.substring(0, 50) : text)
                            + ",\"priority\":\"normal\"}"));
        }
        return plans;
    }

    private String composeAnswer(List<Msg> toolMessages) {
        StringBuilder sb = new StringBuilder();
        boolean anyFailure = false;
        for (Msg m : toolMessages) {
            String toolContent = m.content() == null ? "" : m.content();
            String toolName = m.name() == null ? "tool" : m.name();
            if (toolContent.startsWith("ERROR:")) {
                anyFailure = true;
                sb.append("- **").append(toolName).append("** 执行失败:")
                        .append(toolContent.substring(6).trim()).append('\n');
            } else if (toolContent.startsWith("知识库检索结果")) {
                sb.append("根据内部知识库,整理如下:\n\n");
                for (String line : toolContent.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("[")) {
                        sb.append("- **").append(t, 0, Math.min(t.length(), 60)).append("**\n");
                    } else if (!t.isEmpty() && !t.contains("请在回答中标注")) {
                        sb.append("  ").append(t).append('\n');
                    }
                }
            } else {
                sb.append("- **").append(toolName).append("**: ").append(toolContent.trim()).append('\n');
            }
        }
        if (anyFailure) {
            sb.append("\n有工具执行失败了,我如实说明原因,建议你稍后重试或回复「转人工」由客服跟进。\n");
        }
        sb.append("\n> 当前运行在**离线演示模式**(mock provider),接入真实模型只需配置 ")
                .append("`agent.llm.api-key` 并把 `agent.provider` 改为 `openai`。");
        return sb.toString();
    }

    private String fallbackAnswer(String userText) {
        return """
                我收到了你的问题:**%s**

                当前服务运行在**离线演示模式**,没有接入真实大模型,所以只能做规则化回复。
                不过整条链路(建连 → 流式下发 → 工具调用 → 结果回填 → 二次生成)都是真实运行的,你可以试试下面这些能触发工具的问题:

                1. `北京今天天气怎么样?我要不要带伞` —— 触发 `get_weather`
                2. `帮我算一下 (128.5 + 71.5) * 3 / 2` —— 触发 `calculator`
                3. `退货的运费谁出?` —— 触发 `search_knowledge_base`(内部 BM25 检索)
                4. `现在几点了` —— 触发 `get_current_time`
                5. `我要转人工投诉` —— 触发 `create_support_ticket`

                配置真实模型:

                ```bash
                export AGENT_LLM_API_KEY=sk-xxx
                java -jar agent-service.jar --agent.provider=openai \\
                     --agent.llm.base-url=https://api.deepseek.com/v1 \\
                     --agent.llm.model=deepseek-chat
                ```
                """.formatted(userText.length() > 60 ? userText.substring(0, 60) + "…" : userText);
    }

    private void streamText(StreamListener listener, String text) {
        // 按 4 字符一帧下发,模拟 token 流,顺便让前端的打字机效果有东西可看
        int step = 4;
        for (int i = 0; i < text.length(); i += step) {
            String chunk = text.substring(i, Math.min(text.length(), i + step));
            listener.onContentDelta(chunk);
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String lastUserText(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content() == null ? "" : messages.get(i).content();
            }
        }
        return "";
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
    }

    private int estimateTokens(LlmRequest request) {
        int chars = 0;
        for (Msg m : request.messages()) {
            chars += m.content() == null ? 0 : m.content().length();
        }
        return chars / 2;
    }
}
