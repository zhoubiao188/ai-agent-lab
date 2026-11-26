package com.example.tool.api;

import com.example.tool.fault.FaultInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ToolApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FaultInjector faults;

    @BeforeEach
    void quiet() {
        // 接口测试不该被随机故障干扰
        faults.configure(1L, false);
    }

    @Test
    void 工具清单给出完整声明() throws Exception {
        // registry 按名字排序:inventory.check, logistics.track, order.query,
        // refund.quote, refund.submit, ticket.create
        mvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[4].name").value("refund.submit"))
                .andExpect(jsonPath("$[4].write").value(true))
                .andExpect(jsonPath("$[4].idempotent").value(true))
                .andExpect(jsonPath("$[5].name").value("ticket.create"))
                .andExpect(jsonPath("$[5].idempotent").value(false));
    }

    @Test
    void 单工具直调返回执行细节() throws Exception {
        mvc.perform(post("/api/tools/order.query/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"A10001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.payload.orderId").value("A10001"));
    }

    @Test
    void 直调也会做参数修复() throws Exception {
        mvc.perform(post("/api/tools/order.query/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"A10002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.repairs").isNotEmpty());
    }

    @Test
    void 参数无法修复时返回明确的错误类型() throws Exception {
        mvc.perform(post("/api/tools/order.query/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"not-an-order\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorKind").value("BAD_ARGUMENT"));
    }

    @Test
    void 未知工具返回400而不是500() throws Exception {
        mvc.perform(post("/api/tools/nope/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 对照实验返回四组配置() throws Exception {
        mvc.perform(post("/api/bench/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tasks\":6,\"seed\":7,\"scale\":0.05}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].policy").value("L0-baseline"))
                .andExpect(jsonPath("$.rows[3].policy").value("L3-full"));
    }
}
