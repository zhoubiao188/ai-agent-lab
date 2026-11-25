package com.example.rag.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("/api/kb/stats 返回语料与索引元信息")
    void stats() throws Exception {
        mockMvc.perform(get("/api/kb/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks").value(org.hamcrest.Matchers.greaterThan(30)))
                .andExpect(jsonPath("$.dim").value(512))
                .andExpect(jsonPath("$.embedding").exists())
                .andExpect(jsonPath("$.byDoc").isArray());
    }

    @Test
    @DisplayName("/api/retrieve 返回带调试字段的命中列表")
    void retrieve() throws Exception {
        mockMvc.perform(post("/api/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"跨域出现两个 Access-Control-Allow-Origin\",\"mode\":\"full\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FULL"))
                .andExpect(jsonPath("$.hits.length()").value(3))
                .andExpect(jsonPath("$.hits[0].chunk.docId").value("spring-cloud-gateway"))
                .andExpect(jsonPath("$.hits[0].rerankScore").exists())
                .andExpect(jsonPath("$.hits[0].bm25Rank").exists());
    }

    @Test
    @DisplayName("非法 mode 返回 400 而不是 500")
    void invalidMode() throws Exception {
        mockMvc.perform(post("/api/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"测试\",\"mode\":\"xxx\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("空 query 触发参数校验")
    void blankQueryRejected() throws Exception {
        mockMvc.perform(post("/api/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("/api/eval/report 返回四种策略的完整报告")
    void evalReport() throws Exception {
        mockMvc.perform(get("/api/eval/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategies.length()").value(4))
                .andExpect(jsonPath("$.deltas.length()").value(3))
                .andExpect(jsonPath("$.details.length()").value(org.hamcrest.Matchers.greaterThan(40)))
                .andExpect(jsonPath("$.strategies[0].byType").isMap());
    }

    @Test
    @DisplayName("/api/ask 按 citations → delta → done 的顺序推送 SSE 事件")
    void askStream() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"三层缓存是怎么解决循环依赖的\",\"mode\":\"full\",\"topK\":3}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = dispatched.getResponse().getContentAsString();
        int citations = body.indexOf("event:citations");
        int delta = body.indexOf("event:delta");
        int done = body.indexOf("event:done");
        org.assertj.core.api.Assertions.assertThat(citations).isGreaterThanOrEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(delta).isGreaterThan(citations);
        org.assertj.core.api.Assertions.assertThat(done).isGreaterThan(delta);
        org.assertj.core.api.Assertions.assertThat(body).contains("spring-boot-internals");
    }
}
