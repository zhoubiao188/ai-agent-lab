package com.example.agent.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("跑一次退款:响应带 X-Trace-Id,返回三个嫌疑和一段结论")
    void runRefund() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"帮我把订单 ORD-100234 的 299 元退款处理一下,不想要了","scenario":"NORMAL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.suspects.length()").value(3))
                .andExpect(jsonPath("$.narrative").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode node = mapper.readTree(body);
        String traceId = node.get("traceId").asText();

        mvc.perform(get("/api/runs/{id}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spans.length()").value(org.hamcrest.Matchers.greaterThan(15)))
                .andExpect(jsonPath("$.decisions.length()").value(org.hamcrest.Matchers.greaterThan(10)))
                .andExpect(jsonPath("$.suspects[0].contributions").exists())
                .andExpect(jsonPath("$.outcomeLabel").value("成功"));
    }

    @Test
    @DisplayName("沉默失败:接口成功、没有 ERROR,但嫌疑列表指向写入 Span")
    void silentFailureSurfacesInSuspects() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"订单 ORD-100236 退款 128 元,质量问题","scenario":"SILENT_FAILURE"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode node = mapper.readTree(body);
        assertThat(node.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(node.get("narrative").asText()).contains("没有 ERROR");
        assertThat(node.get("suspects").get(0).get("name").asText()).isEqualTo("write refund.commit");
    }

    @Test
    @DisplayName("元信息:20 个决策点 / 6 个剧本 / 12 个工具")
    void catalog() throws Exception {
        mvc.perform(get("/api/meta/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(20))
                .andExpect(jsonPath("$.scenarios.length()").value(6))
                .andExpect(jsonPath("$.toolCount").value(12))
                .andExpect(jsonPath("$.decisions[0].symptom").isNotEmpty());
    }

    @Test
    @DisplayName("列表与聚合指标可用")
    void listAndOverview() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"查一下 SKU-201 还有库存吗","scenario":"RETRY_STORM"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/runs").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[0].topSuspect").isNotEmpty());

        mvc.perform(get("/api/metrics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.p95Ms").isNumber());
    }

    @Test
    @DisplayName("JSONL 导出:每行一个 Span/决策,可直接 grep")
    void exportJsonl() throws Exception {
        String body = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"退款政策是怎么规定的","scenario":"COST_BLOWUP"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String traceId = mapper.readTree(body).get("traceId").asText();

        String jsonl = mvc.perform(get("/api/runs/{id}/export", traceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String[] lines = jsonl.strip().split("\n");
        assertThat(lines.length).isGreaterThan(15);
        assertThat(jsonl).contains("\"kind\":\"LLM\"");
        assertThat(jsonl).contains("\"type\":\"decision\"");
    }

    @Test
    @DisplayName("参数校验与 404")
    void validation() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\",\"scenario\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(get("/api/runs/not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("评测接口:小样本也能返回完整结构")
    void benchEndpoint() throws Exception {
        mvc.perform(post("/api/bench/triage").param("incidents", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families.length()").value(5))
                .andExpect(jsonPath("$.overall.recall3").isNumber())
                .andExpect(jsonPath("$.baselines.length()").value(3));
    }
}
