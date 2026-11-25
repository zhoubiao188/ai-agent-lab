package com.example.rag.llm;

import com.example.rag.chunk.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * OpenAI 兼容的流式聊天客户端(DeepSeek / 通义 / Moonshot / vLLM / Ollama 通用)。
 * <p>SSE 解析最容易踩的坑:data 行可能是多行拼接、可能是 [DONE]、可能在半行处被 TCP 切断。
 * 这里按行读、跳过空行与注释行、只在拿到完整 JSON 后才增量取 delta.content。</p>
 */
public class OpenAiCompatLlmClient implements LlmClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public OpenAiCompatLlmClient(String baseUrl, String apiKey, String model, double temperature) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "openai-compatible/" + model;
    }

    @Override
    public void streamAnswer(String question, List<Chunk> contexts, Consumer<String> onDelta) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("stream", true);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", PromptBuilder.SYSTEM);
            messages.addObject().put("role", "user").put("content", PromptBuilder.user(question, contexts));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("chat HTTP " + resp.statusCode() + ": " + err);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode node = mapper.readTree(payload);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        onDelta.accept(delta.asText());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("调用生成模型失败: " + e.getMessage(), e);
        }
    }
}
