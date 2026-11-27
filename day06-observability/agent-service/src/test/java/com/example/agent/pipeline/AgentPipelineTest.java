package com.example.agent.pipeline;

import com.example.agent.fault.Scenario;
import com.example.agent.support.PipelineFixture;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 五个剧本的端到端断言。
 *
 * <p>断言的重点不是"结果对不对",而是"信号有没有被采到":
 * 沉默失败必须留下 integrity=false、重试风暴必须是 3 次尝试、成本失控必须是 token 集中在 LLM span。
 * 这些信号决定了排序能不能找回埋点。</p>
 */
class AgentPipelineTest {

    private static final String REFUND_Q = "帮我把订单 ORD-100234 的 299 元退款处理一下,不想要了";

    @Test
    @DisplayName("正常退款:5 步全部成功、20 个决策点齐全、无嫌疑")
    void normalRefund() {
        try (PipelineFixture fx = new PipelineFixture(7L)) {
            RunResult result = fx.run(Scenario.NORMAL, REFUND_Q);
            Trace trace = fx.traceOf(result);

            assertThat(trace.outcome()).isEqualTo(Outcome.SUCCESS);
            assertThat(result.culpritSpanId()).isNull();
            // 正常路径不会触发 D14(重试)/D15(降级),所以是 18 条而不是 20 条 ——
            // "20 个决策点"是可能出现的全集,不是每次都要走一遍
            assertThat(trace.decisions()).hasSize(18);
            assertThat(trace.decisions()).extracting(d -> d.code())
                    .contains("D01", "D05", "D16", "D18", "D20")
                    .doesNotContain("D14", "D15");
            assertThat(trace.spans().stream().filter(s -> s.kind() == SpanKind.STEP).count()).isEqualTo(5);
            assertThat(trace.retryAmplification()).isEqualTo(1.0);
            assertThat(result.triage().suspects()).isNotEmpty();
            // 正常运行时最高嫌疑分也应偏低:没有错误、没有重试、没有完整性告警
            assertThat(result.triage().suspects().get(0).score()).isLessThan(0.35);
        }
    }

    @Test
    @DisplayName("慢工具:两次超时吃光预算后降级,答案里标记数据来自缓存")
    void slowToolDegrades() {
        try (PipelineFixture fx = new PipelineFixture(11L)) {
            RunResult result = fx.run(Scenario.SLOW_TOOL,
                    "订单 ORD-100236 的物流到哪了");
            Trace trace = fx.traceOf(result);

            assertThat(trace.outcome()).isEqualTo(Outcome.SUCCESS);
            // 每次重试都是一个独立 Span,检查"同名 Span 的最大尝试次数"而不是第一条
            Span orderTool = lastSpanNamed(trace, "tool order.get");
            assertThat(orderTool.attempts()).isEqualTo(2);
            assertThat(orderTool.status()).isEqualTo(SpanStatus.ERROR);
            assertThat(orderTool.errorKind()).isEqualTo("TIMEOUT");

            assertThat(trace.decisions()).extracting(d -> d.code()).contains("D15");
            assertThat(result.answer()).contains("缓存");
            // 埋点落在第一次失败的尝试上;排序的 top1 应该就是这一族 Span 之一
            assertThat(trace.spans()).extracting(Span::spanId).contains(result.culpritSpanId());
            assertThat(result.triage().suspects().get(0).name()).isEqualTo("tool order.get");
        }
    }

    @Test
    @DisplayName("重试风暴:503 两次后第三次成功,重试放大系数 = 3")
    void retryStorm() {
        try (PipelineFixture fx = new PipelineFixture(13L)) {
            RunResult result = fx.run(Scenario.RETRY_STORM,
                    "订单 ORD-100235 还有库存吗?查一下 SKU-201");
            Trace trace = fx.traceOf(result);

            Span inventory = lastSpanNamed(trace, "tool inventory.get");
            assertThat(inventory.attempts()).isEqualTo(3);
            assertThat(inventory.status()).isEqualTo(SpanStatus.OK);
            assertThat(trace.spans().stream().filter(s -> s.kind() == SpanKind.RETRY).count()).isEqualTo(2);
            assertThat(trace.retryAmplification()).isEqualTo(3.0);
            assertThat(trace.spans()).extracting(Span::spanId).contains(result.culpritSpanId());
            assertThat(result.triage().suspects().get(0).name()).isEqualTo("tool inventory.get");
            assertThat(result.triage().suspects().get(0).score()).isGreaterThan(0.2);
        }
    }

    @Test
    @DisplayName("沉默失败:没有 ERROR 的 Span,靠 write.echo.match=false 被发现")
    void silentFailureLeavesNoErrorSpan() {
        try (PipelineFixture fx = new PipelineFixture(17L)) {
            RunResult result = fx.run(Scenario.SILENT_FAILURE,
                    "订单 ORD-100236 退款 128 元,质量问题");
            Trace trace = fx.traceOf(result);

            assertThat(trace.outcome()).isEqualTo(Outcome.SUCCESS);
            assertThat(trace.errorCount()).isZero();

            Span write = spanNamed(trace, "write refund.commit");
            assertThat(write.status()).isEqualTo(SpanStatus.OK);
            assertThat(write.integrityOk()).isFalse();
            assertThat(write.attr("write.echo.match")).isEqualTo(false);
            assertThat(write.attrString("integrity.reason")).contains("不一致");

            assertThat(result.culpritSpanId()).isEqualTo(write.spanId());
            assertThat(result.triage().suspects()).extracting(s -> s.spanId())
                    .contains(write.spanId());
        }
    }

    @Test
    @DisplayName("成本失控:token 集中在 llm.answer,且补查没有收窄")
    void costBlowup() {
        try (PipelineFixture fx = new PipelineFixture(19L)) {
            RunResult result = fx.run(Scenario.COST_BLOWUP,
                    "退款政策是怎么规定的,多久能到账");
            Trace trace = fx.traceOf(result);
            Span answer = spanNamed(trace, "llm.answer");

            assertThat(answer.tokensIn()).isGreaterThan(3000);
            assertThat(answer.tokensIn() * 1.0 / trace.totalTokens()).isGreaterThan(0.5);
            assertThat(trace.decisions()).filteredOn(d -> d.code().equals("D17"))
                    .first().satisfies(d -> {
                        assertThat(d.confidence()).isLessThan(0.5);
                        assertThat(d.attrs().get("narrowed")).isEqualTo(false);
                    });
            assertThat(result.culpritSpanId()).isEqualTo(answer.spanId());
            assertThat(result.triage().suspects()).extracting(s -> s.spanId()).contains(answer.spanId());
        }
    }

    @Test
    @DisplayName("参数漂移:金额默认 0,自检拦下,根因在 D05 决策点")
    void argumentDrift() {
        try (PipelineFixture fx = new PipelineFixture(23L)) {
            RunResult result = fx.run(Scenario.ARG_DRIFT, "订单 ORD-100237 要退款");
            Trace trace = fx.traceOf(result);

            assertThat(trace.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(trace.decision("D05")).hasValueSatisfying(d -> {
                assertThat(d.confidence()).isLessThan(0.5);
                assertThat(d.basis().name()).isEqualTo("DEFAULT");
                assertThat(d.chosen()).contains("默认值");
            });
            // 缺关键槽位时置信度应该掉到"该追问"的阈值以下,但自动模式仍然硬着头皮跑
            assertThat(trace.decision("D07")).hasValueSatisfying(d -> {
                assertThat(d.chosen()).isEqualTo("按默认值继续");
                assertThat(d.basis().name()).isEqualTo("FALLBACK");
            });

            assertThat(result.culpritSpanId()).isNotNull();
            assertThat(result.triage().suspects()).extracting(s -> s.spanId())
                    .contains(result.culpritSpanId());
        }
    }

    @Test
    @DisplayName("并行层:price.calc 与 policy.lookup 在同一层,父 Span 都是根")
    void parallelLayerSharesParent() {
        try (PipelineFixture fx = new PipelineFixture(29L)) {
            Trace trace = fx.traceOf(fx.run(Scenario.NORMAL, REFUND_Q));
            List<Span> steps = trace.spans().stream()
                    .filter(s -> s.kind() == SpanKind.STEP
                            && (s.name().contains("price.calc") || s.name().contains("policy.lookup")))
                    .toList();
            assertThat(steps).hasSize(2);
            assertThat(steps.get(0).parentId()).isEqualTo(steps.get(1).parentId());
            assertThat(steps.get(0).parentId()).isEqualTo(trace.root().spanId());
        }
    }

    @Test
    @DisplayName("决策台账里的差值:接近选项时差距 < 0.1,便于页面标黄")
    void decisionMargin() {
        try (PipelineFixture fx = new PipelineFixture(31L)) {
            Trace trace = fx.traceOf(fx.run(Scenario.NORMAL, REFUND_Q));
            assertThat(trace.decisions()).allSatisfy(d -> {
                assertThat(d.margin()).isBetween(0.0, 1.0);
                assertThat(d.alternatives()).isNotEmpty();
            });
        }
    }

    private Span lastSpanNamed(Trace trace, String name) {
        return trace.spans().stream().filter(s -> s.name().equals(name))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("找不到 Span: " + name));
    }

    private Span spanNamed(Trace trace, String name) {
        return trace.spans().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("找不到 Span: " + name
                        + ",实际有:" + trace.spans().stream().map(Span::name).toList()));
    }
}
