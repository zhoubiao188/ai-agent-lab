package com.example.rag.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAI 兼容的 /embeddings 客户端。
 * <p>DeepSeek 目前没有 embedding 接口,常见组合是 bge-m3(硅基流动)、通义 text-embedding-v3、
 * 或者本地 Ollama 的 nomic-embed-text。它们都提供 OpenAI 兼容的 /v1/embeddings,
 * 所以这一份代码可以通用。</p>
 *
 * <p>三个工程细节:批量请求(单条请求的 HTTP 开销远大于推理本身)、
 * 按 index 字段回填顺序(批量接口不保证返回顺序)、失败重试一次(embedding 是幂等的)。</p>
 */
public class OpenAiEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int batchSize;
    private final int dim;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public OpenAiEmbeddingModel(String baseUrl, String apiKey, String model, int batchSize, int dim) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.batchSize = Math.max(1, batchSize);
        this.dim = dim;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public String name() {
        return "openai-compatible/" + model;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(texts.size(), from + batchSize));
            out.addAll(requestWithRetry(slice));
        }
        return out;
    }

    private List<float[]> requestWithRetry(List<String> slice) {
        try {
            return request(slice);
        } catch (Exception first) {
            log.warn("embedding 请求失败,重试一次: {}", first.toString());
            try {
                Thread.sleep(200);
                return request(slice);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("embedding 被中断", ie);
            } catch (Exception second) {
                throw new IllegalStateException("embedding 请求连续失败", second);
            }
        }
    }

    private List<float[]> request(List<String> slice) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        slice.forEach(input::add);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("embeddings HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != slice.size()) {
            throw new IllegalStateException("embeddings 返回条数不符: " + data.size() + " != " + slice.size());
        }
        List<Slot> slots = new ArrayList<>();
        for (JsonNode item : data) {
            int idx = item.path("index").asInt();
            JsonNode vec = item.path("embedding");
            float[] v = new float[vec.size()];
            double sum = 0;
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) vec.get(i).asDouble();
                sum += (double) v[i] * v[i];
            }
            if (sum > 1e-12) {
                float inv = (float) (1.0 / Math.sqrt(sum));
                for (int i = 0; i < v.length; i++) {
                    v[i] *= inv;
                }
            }
            if (v.length != dim) {
                throw new IllegalStateException("向量维度与配置不符: " + v.length + " != " + dim);
            }
            slots.add(new Slot(idx, v));
        }
        // 批量接口不保证返回顺序,必须按 index 重排
        slots.sort(Comparator.comparingInt(Slot::index));
        List<float[]> out = new ArrayList<>(slots.size());
        slots.forEach(s -> out.add(s.vector()));
        return out;
    }

    private record Slot(int index, float[] vector) {
    }
}
