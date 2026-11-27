package com.example.multiagent.api;

import com.example.multiagent.support.BaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiTest extends BaseTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("任务清单带金标准,前端才能画'覆盖了几个诉求'")
    void tasks() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].id").value("T01"))
                .andExpect(jsonPath("$[0].slots.length()").value(5));
    }

    @Test
    @DisplayName("四组配置的名字和开关都暴露给前端")
    void policies() throws Exception {
        mvc.perform(get("/api/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].name").value("C0-无契约"))
                .andExpect(jsonPath("$[3].replan").value(true));
    }

    @Test
    @DisplayName("未知任务返回 400 而不是 500")
    void unknownTask() throws Exception {
        mvc.perform(post("/api/agent/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"T99\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("对照实验接口:两个任务也能跑出四行")
    void bench() throws Exception {
        mvc.perform(get("/api/bench").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.details.length()").value(2));
    }
}
