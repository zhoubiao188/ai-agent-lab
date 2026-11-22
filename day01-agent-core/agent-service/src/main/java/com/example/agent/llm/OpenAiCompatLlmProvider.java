package com.example.agent.llm;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.dto.LlmRequest;
import com.example.agent.llm.dto.Msg;
import com.example.agent.llm.dto.ToolCall;
import com.example.agent.llm.dto.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容协议的流式实现。
 * <p>一套代码通吃 OpenAI / DeepSeek / 通义 / Moonshot / vLLM / Ollama(/v1) —— 只要它认
 * {@code POST /chat/completions} 且支持 {@code stream=true}。</p>
 *
 * <p>几个必须处理的协议细节:</p>
 * <ol>
 *   <li>SSE 分帧以 {@code data: } 开头,以空行结束,不能按"一个 JSON 一行"来解析;</li>
 *   <li>{@code tool_calls} 是<b>增量</b>的:第一帧带 id 和 function.name,后续帧只带
 *       function.arguments 的片段,必须按 index 聚合;</li>
 *   <li>{@code [DONE]} 之后不要再读流;</li>
 *   <li>usage 只在最后一帧(且需显式开启 {@code stream_options.include_usage})才有。</li>
 * </ol>
 */
public class OpenAiCompatLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmProvider.class);

    private final AgentProperties.Llm cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatLlmProvider(AgentProperties.Llm cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(cfg.getConnectTimeout() == null ? Duration.ofSeconds(10) : cfg.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public void stream(LlmRequest request, StreamListener listener) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            listener.onError(new IllegalStateException(
                    "未配置 agent.llm.api-key,无法调用真实模型;可先用 agent.provider=mock 体验完整链路"));
            return;
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(trimSlash(cfg.getBaseUrl()) + "/chat/completions"))
                    .timeout(cfg.getReadTimeout() == null ? Duration.ofSeconds(90) : cfg.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            listener.onError(e);
            return;
        }

        try {
            HttpResponse<InputStream> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                listener.onError(new IllegalStateException(
                        "LLM 返回 %d: %s".formatted(response.statusCode(), truncate(body, 500))));
                return;
            }
            readStream(response.body(), listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(e);
        } catch (java.io.IOException e) {
            listener.onError(e);
        }
    }

    private void readStream(InputStream in, StreamListener listener) throws java.io.IOException {
        String finishReason = null;
        TokenUsage usage = TokenUsage.unknown();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue; // 注释帧/心跳
                }
                if (!line.startsWith("data:")) {
                    continue; // event: / id: 等字段忽略
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk = mapper.readTree(data);

                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    usage = new TokenUsage(usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0));
                }

                JsonNode choices = chunk.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    JsonNode content = delta.get("content");
                    if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                        listener.onContentDelta(content.asText());
                    }
                    JsonNode reasoning = delta.get("reasoning_content");
                    if (reasoning != null && !reasoning.isNull() && !reasoning.asText().isEmpty()) {
                        // 推理型模型(deepseek-r1 等)的思考过程,这里选择不展示,仅记录
                        log.debug("reasoning delta: {}", reasoning.asText().length());
                    }
                    JsonNode toolCalls = delta.get("tool_calls");
                    if (toolCalls != null && toolCalls.isArray()) {
                        for (JsonNode tc : toolCalls) {
                            int index = tc.path("index").asInt(0);
                            String id = tc.path("id").isMissingNode() ? null : tc.path("id").asText(null);
                            JsonNode fn = tc.get("function");
                            String name = fn == null ? null : fn.path("name").asText(null);
                            String args = fn == null ? null : fn.path("arguments").asText(null);
                            listener.onToolCallDelta(index, id, name, args);
                        }
                    }
                }
                JsonNode fr = choice.get("finish_reason");
                if (fr != null && !fr.isNull()) {
                    finishReason = fr.asText();
                }
            }
        }
        listener.onFinish(finishReason, usage);
    }

    private String buildBody(LlmRequest request) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.putObject("stream_options").put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        for (Msg m : request.messages()) {
            messages.add(toWire(m));
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", spec.parameters());
            }
            body.put("tool_choice", "auto");
        }
        return mapper.writeValueAsString(body);
    }

    private ObjectNode toWire(Msg m) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", m.role());
        if (m.content() != null) {
            node.put("content", m.content());
        }
        if ("tool".equals(m.role())) {
            node.put("tool_call_id", m.toolCallId());
            if (m.name() != null) {
                node.put("name", m.name());
            }
        }
        if (m.hasToolCalls()) {
            ArrayNode arr = node.putArray("tool_calls");
            for (ToolCall call : m.toolCalls()) {
                ObjectNode c = arr.addObject();
                c.put("id", call.id());
                c.put("type", "function");
                ObjectNode fn = c.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.arguments());
            }
        }
        return node;
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
