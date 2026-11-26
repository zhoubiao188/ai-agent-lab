package com.example.tool.agent;

import com.example.tool.exec.ResilienceRegistry;
import com.example.tool.exec.RunPolicy;
import com.example.tool.exec.ToolResult;
import com.example.tool.fault.FaultInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentOrchestratorTest {

    @Autowired
    private AgentOrchestrator orchestrator;

    @Autowired
    private FaultInjector faults;

    @Autowired
    private ResilienceRegistry resilience;

    @BeforeEach
    void reset() {
        resilience.reset();
        faults.clearForced();
    }

    @Test
    void 没有故障时全链路成功() {
        faults.configure(1L, false);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();

        TaskOutcome outcome = orchestrator.run("处理订单 A10002 的退款申请,并跟进物流异常",
                "r1", "t1", RunPolicy.FULL, events::add);

        assertThat(outcome.status()).isEqualTo(TaskOutcome.Status.SUCCESS);
        assertThat(outcome.results()).hasSize(6).allMatch(ToolResult::ok);
        assertThat(outcome.retries()).isZero();
        assertThat(outcome.repairs()).isZero();
        assertThat(outcome.answer()).contains("退款").contains("A10002");
        assertThat(events).extracting(AgentEvent::type)
                .contains("plan", "planned", "tool_ok", "batch_summary", "answer", "done");
    }

    @Test
    void 依赖被解析成上游真实值而不是占位符() {
        faults.configure(1L, false);

        TaskOutcome outcome = orchestrator.run("处理订单 A10004 的退款申请",
                "r2", "t2", RunPolicy.FULL, EventSink.NOOP);

        ToolResult submit = outcome.results().stream()
                .filter(r -> r.tool().equals("refund.submit")).findFirst().orElseThrow();
        assertThat(submit.ok()).isTrue();
        assertThat(submit.payload().get("refundId").asText()).startsWith("R");
        assertThat(submit.payload().get("amount").asDouble()).isGreaterThan(0);
    }

    @Test
    void 开启故障注入后依然给出可读结论() {
        faults.configure(20261126L, true);
        // 强制物流接口一直 5xx:重试必然发生,随后走降级
        faults.force(new com.example.tool.fault.FaultInjector.ForcedFault(
                "logistics.track", com.example.tool.exec.ErrorKind.UPSTREAM_5XX, "forced"));

        TaskOutcome outcome = orchestrator.run("处理订单 A10006 的退款申请,并跟进物流异常",
                "r3", "t3", RunPolicy.FULL, EventSink.NOOP);

        assertThat(outcome.callCount()).isEqualTo(6);
        assertThat(outcome.answer()).isNotBlank();
        assertThat(outcome.retries()).isGreaterThan(0);
        assertThat(outcome.status()).isEqualTo(TaskOutcome.Status.DEGRADED);
    }
}
