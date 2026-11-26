package com.example.tool.agent;

import com.example.tool.exec.ToolCall;
import com.example.tool.fault.FaultInjector;
import com.example.tool.spec.ToolRegistry;
import com.example.tool.spec.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.tool.support.TestSupport.corrupting;
import static com.example.tool.support.TestSupport.props;
import static org.assertj.core.api.Assertions.assertThat;

class MockPlannerTest {

    private MockPlanner planner(FaultInjector faults) {
        return new MockPlanner(faults, new ToolRegistry());
    }

    @Test
    void 生成的调用图依赖关系正确() {
        MockPlanner p = planner(new FaultInjector(props()));
        List<ToolCall> calls = p.plan("处理订单 A10007 的退款申请", "t1");

        assertThat(calls).hasSize(6);
        assertThat(calls.get(0).id()).isEqualTo("t1c1");
        assertThat(calls.get(0).args().get("orderId").asText()).isEqualTo("A10007");
        // 报价单依赖订单,退款依赖报价单
        assertThat(calls.get(3).dependsOn()).containsExactly("t1c1");
        assertThat(calls.get(4).dependsOn()).containsExactly("t1c4");
        // 物流、库存、工单都是独立调用,应该能被并行调度
        assertThat(calls.get(1).dependsOn()).isEmpty();
        assertThat(calls.get(2).dependsOn()).isEmpty();
        assertThat(calls.get(5).dependsOn()).isEmpty();
    }

    @Test
    void 同一任务两次规划得到完全一致的结果() {
        FaultInjector faults = new FaultInjector(corrupting(1.0));
        List<ToolCall> first = planner(faults).plan("处理订单 A10002 的退款申请", "t3");
        List<ToolCall> second = planner(faults).plan("处理订单 A10002 的退款申请", "t3");

        assertThat(first.toString()).isEqualTo(second.toString());
    }

    @Test
    void 注入器关闭时不污染参数() {
        FaultInjector faults = new FaultInjector(props());
        List<ToolCall> calls = planner(faults).plan("处理订单 A10003 的退款申请", "t4");
        assertThat(calls.get(0).args().get("orderId").asText()).isEqualTo("A10003");
    }

    @Test
    void 污染后参数不再是干净的() {
        String task = "处理订单 A10005 的退款申请";
        List<ToolCall> dirty = planner(new FaultInjector(corrupting(1.0))).plan(task, "t5");
        List<ToolCall> clean = planner(new FaultInjector(props())).plan(task, "t5");

        assertThat(clean.toString()).doesNotContain("order_id", "timestamp");
        assertThat(dirty.toString()).isNotEqualTo(clean.toString());
    }

    @Test
    void 模型重想会补上能从任务推断的字段() {
        MockPlanner p = planner(new FaultInjector(props()));
        ToolCall call = p.plan("处理订单 A10009 的退款申请", "t6").get(0);
        ToolCall missing = call.withArgs(com.example.tool.support.TestSupport.obj("paidAmount", 1));

        ToolCall rewritten = p.rewrite(missing,
                List.of(new Violation("/orderId", Violation.Code.MISSING_REQUIRED, "缺少必填字段 orderId")),
                "处理订单 A10009 的退款申请");

        assertThat(rewritten.args().get("orderId").asText()).isEqualTo("A10009");
    }

    @Test
    void 推断不出来的字段不会被瞎填() {
        MockPlanner p = planner(new FaultInjector(props()));
        ToolCall call = p.plan("处理订单 A10010 的退款申请", "t7").get(4)
                .withArgs(com.example.tool.support.TestSupport.obj("amount", 1));

        ToolCall rewritten = p.rewrite(call,
                List.of(new Violation("/quoteId", Violation.Code.MISSING_REQUIRED, "缺少必填字段 quoteId")),
                "处理订单 A10010 的退款申请");

        assertThat(rewritten.args().has("quoteId")).isFalse();
    }
}
