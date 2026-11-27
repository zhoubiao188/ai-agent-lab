package com.example.o11y;

import com.example.o11y.attr.AttrKeys;
import com.example.o11y.clock.VirtualClock;
import com.example.o11y.decision.Basis;
import com.example.o11y.decision.Decision;
import com.example.o11y.decision.DecisionPoint;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.IntegrityGrade;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.Trace;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.trace.Tracer;
import com.example.o11y.triage.Suspect;
import com.example.o11y.triage.TriageEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六个信号各自能不能单独把目标捞出来,以及优先级是否正确。
 *
 * <p>这类测试用真实的 Tracer 构造,而不是 new Span(...) 拼假数据:
 * 假数据测不出"父子关系没挂上导致 selfTime 算错"这类问题。</p>
 */
class TriageEngineTest {

    private final TriageEngine engine = new TriageEngine();

    private record Rig(VirtualClock clock, Tracer tracer, InMemoryTraceStore store) {
    }

    private Rig rig() {
        VirtualClock clock = new VirtualClock();
        InMemoryTraceStore store = new InMemoryTraceStore(16);
        return new Rig(clock, new Tracer(clock, new Redactor(), new TailSampler(1.0, 0.0), store), store);
    }

    @Test
    @DisplayName("耗时信号:慢工具排第一,即使它没有报错")
    void slowSpanWins() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("s1", "SLOW_TOOL", "q", "u");
        try (Tracer.Scope step = rig.tracer().span("step 1 order.get", SpanKind.STEP)) {
            try (Tracer.Scope slow = rig.tracer().span("tool order.get", SpanKind.TOOL)) {
                rig.clock().sleep(400);
            }
            try (Tracer.Scope fast = rig.tracer().span("tool price.calc", SpanKind.TOOL)) {
                rig.clock().sleep(20);
            }
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);

        List<Suspect> suspects = engine.rank(rig.store().find(trace.traceId()).orElseThrow());
        assertThat(suspects.get(0).name()).isEqualTo("tool order.get");
        assertThat(suspects.get(0).contributions().get("selfMs")).isGreaterThan(0.1);
        // 根 Span 永远不进候选:它是汇总,不是原因
        assertThat(suspects).extracting(Suspect::kind).doesNotContain(SpanKind.RUN);
    }

    @Test
    @DisplayName("错误信号:最深的那一层负责,父节点只拿衰减分")
    void deepestErrorWins() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("e1", "RETRY_STORM", "q", "u");
        try (Tracer.Scope outer = rig.tracer().span("step 1 inventory.get", SpanKind.STEP)) {
            try (Tracer.Scope inner = rig.tracer().span("tool inventory.get", SpanKind.TOOL)) {
                inner.error("UPSTREAM_5XX", "503");
            }
        }
        rig.tracer().finish(trace, Outcome.FAILED);

        List<Suspect> suspects = engine.rank(rig.store().find(trace.traceId()).orElseThrow());
        Suspect tool = suspects.stream().filter(s -> s.name().startsWith("tool")).findFirst().orElseThrow();
        Suspect step = suspects.stream().filter(s -> s.name().startsWith("step")).findFirst().orElseThrow();
        assertThat(tool.score()).isGreaterThan(step.score());
        assertThat(tool.score()).isGreaterThan(0.2);
    }

    @Test
    @DisplayName("完整性信号:没有 ERROR 也能排到第一")
    void integrityWinsWithoutError() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("i1", "SILENT_FAILURE", "q", "u");
        try (Tracer.Scope step = rig.tracer().span("step 5 refund.commit", SpanKind.STEP)) {
            try (Tracer.Scope write = rig.tracer().span("write refund.commit", SpanKind.WRITE)) {
                write.tag("write.echo.match", false).integrity(false, "回显金额 0 != 入参 12800");
                rig.clock().sleep(90);
            }
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);

        Trace saved = rig.store().find(trace.traceId()).orElseThrow();
        assertThat(saved.errorCount()).isZero();
        List<Suspect> suspects = engine.rank(saved);
        assertThat(suspects.get(0).name()).isEqualTo("write refund.commit");
        assertThat(suspects.get(0).evidence()).anyMatch(e -> e.contains("已落地"));
    }

    @Test
    @DisplayName("护栏拦下:完整性分数打对折,低置信度决策反超")
    void caughtIntegrityYieldsToUpstreamDecision() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("c1", "ARG_DRIFT", "q", "u");
        try (Tracer.DecisionScope d = rig.tracer().decision(DecisionPoint.D05_ARG_BIND)) {
            d.record(Decision.at(DecisionPoint.D05_ARG_BIND)
                    .chosen("默认值兜底").basis(Basis.DEFAULT).confidence(0.3)
                    .alternative("全部来自原话", 1, "用户明确给出")
                    .alternative("默认值兜底", 1, "缺失槽位填 0")
                    .build());
        }
        try (Tracer.Scope step = rig.tracer().span("step 4 refund.preview", SpanKind.STEP)) {
            try (Tracer.Scope tool = rig.tracer().span("tool refund.preview", SpanKind.TOOL)) {
                // 护栏:预演拒绝执行,状态没被改。真正错的是它收到的参数。
                tool.integrity(false, "业务试算未通过:金额超出实付或为 0", IntegrityGrade.CAUGHT);
            }
        }
        rig.tracer().finish(trace, Outcome.FAILED);

        List<Suspect> suspects = engine.rank(rig.store().find(trace.traceId()).orElseThrow());
        assertThat(suspects.get(0).decisionCode()).isEqualTo("D05");
        Suspect guard = suspects.stream().filter(s -> s.name().equals("tool refund.preview"))
                .findFirst().orElseThrow();
        assertThat(guard.evidence()).anyMatch(e -> e.contains("护栏"));
    }

    @Test
    @DisplayName("决策信号:低置信度决策能进前三")
    void lowConfidenceDecisionRanksHigh() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("d1", "ARG_DRIFT", "q", "u");
        try (Tracer.DecisionScope d = rig.tracer().decision(DecisionPoint.D05_ARG_BIND)) {
            d.record(Decision.at(DecisionPoint.D05_ARG_BIND)
                    .chosen("默认值兜底").basis(Basis.DEFAULT).confidence(0.3)
                    .alternative("全部来自原话", 1, "用户明确给出")
                    .alternative("默认值兜底", 1, "缺失槽位填 0")
                    .build());
        }
        try (Tracer.Scope tool = rig.tracer().span("tool refund.preview", SpanKind.TOOL)) {
            rig.clock().sleep(40);
        }
        rig.tracer().finish(trace, Outcome.FAILED);

        Trace saved = rig.store().find(trace.traceId()).orElseThrow();
        List<Suspect> suspects = engine.rank(saved);
        assertThat(suspects.subList(0, Math.min(3, suspects.size())))
                .extracting(Suspect::decisionCode).contains("D05");
        // 决策证据里要能读出"依据是默认值"
        Suspect d05 = suspects.stream().filter(s -> "D05".equals(s.decisionCode())).findFirst().orElseThrow();
        assertThat(d05.evidence()).anyMatch(e -> e.contains("默认值"));
    }

    @Test
    @DisplayName("成本信号:token 集中的 Span 靠前,且 token 归一化不因\"只有一次调用\"而虚高")
    void tokenHeavySpanRanksHigh() throws Exception {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("c1", "COST_BLOWUP", "q", "u");
        try (Tracer.Scope llm = rig.tracer().span("llm.answer", SpanKind.LLM)) {
            llm.tokens(9000, 400, 10600);
            rig.clock().sleep(120);
        }
        try (Tracer.Scope small = rig.tracer().span("tool doc.search", SpanKind.TOOL)) {
            small.tokens(50, 0, 50);
            rig.clock().sleep(60);
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);

        List<Suspect> suspects = engine.rank(rig.store().find(trace.traceId()).orElseThrow());
        assertThat(suspects.get(0).name()).isEqualTo("llm.answer");
        assertThat(suspects.get(0).contributions().get("tokens")).isGreaterThan(0.1);
    }

    @Test
    @DisplayName("排序稳定:同分时按 Span 出现顺序,且分数单调不增")
    void deterministicOrder() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("o1", "NORMAL", "q", "u");
        for (int i = 0; i < 3; i++) {
            try (Tracer.Scope s = rig.tracer().span("tool t" + i, SpanKind.TOOL)) {
                rig.clock().sleep(30);
            }
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);
        Trace saved = rig.store().find(trace.traceId()).orElseThrow();

        List<Suspect> first = engine.rank(saved);
        List<Suspect> second = engine.rank(saved);
        assertThat(first).extracting(Suspect::spanId).isEqualTo(second.stream().map(Suspect::spanId).toList());
        for (int i = 1; i < first.size(); i++) {
            assertThat(first.get(i - 1).score()).isGreaterThanOrEqualTo(first.get(i).score());
        }
    }

    @Test
    @DisplayName("自动生成的一句话结论包含结果与首要嫌疑")
    void narrative() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("n1", "SILENT_FAILURE", "q", "u");
        try (Tracer.Scope write = rig.tracer().span("write refund.commit", SpanKind.WRITE)) {
            write.integrity(false, "回显金额 0");
            rig.clock().sleep(50);
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);

        var triage = engine.triage(rig.store().find(trace.traceId()).orElseThrow(), 3);
        assertThat(triage.narrative()).contains("成功").contains("write refund.commit").contains("没有 ERROR");
        assertThat(triage.spanCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("权重可替换:把耗时权重压到 0,慢 Span 不再排第一")
    void customWeights() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("w1", "NORMAL", "q", "u");
        try (Tracer.Scope slow = rig.tracer().span("tool slow", SpanKind.TOOL)) {
            rig.clock().sleep(300);
        }
        try (Tracer.Scope bad = rig.tracer().span("tool bad", SpanKind.TOOL)) {
            bad.error("BUSINESS", "拒绝");
        }
        rig.tracer().finish(trace, Outcome.SUCCESS);
        Trace saved = rig.store().find(trace.traceId()).orElseThrow();

        // 默认权重下"报错"压过"慢":错误是确定性损失,慢只是体验损失
        assertThat(engine.rank(saved).get(0).name()).isEqualTo("tool bad");
        // 把错误权重清零之后,排第一的应该换成慢的那个
        TriageEngine noErr = new TriageEngine(new TriageEngine.Weights(0.20, 0.0, 0.10, 0.14, 0.16, 0.18));
        assertThat(noErr.rank(saved).get(0).name()).isEqualTo("tool slow");
    }

    @Test
    @DisplayName("证据覆盖:同分决策挤满前二时,完整性证据仍要进清单")
    void integrityEvidenceAlwaysSurvivesTopN() {
        Rig rig = rig();
        Trace trace = rig.tracer().begin("e1", "ARG_DRIFT", "q", "u");
        for (DecisionPoint point : List.of(DecisionPoint.D05_ARG_BIND, DecisionPoint.D06_ARG_COERCE,
                DecisionPoint.D02_SLOT_AUDIT)) {
            try (Tracer.DecisionScope d = rig.tracer().decision(point)) {
                d.record(Decision.at(point).chosen("默认值兜底").basis(Basis.DEFAULT).confidence(0.15)
                        .alternative("全部来自原话", 1, "用户明确给出")
                        .alternative("默认值兜底", 1, "缺失槽位填 0")
                        .build());
            }
        }
        try (Tracer.Scope step = rig.tracer().span("step 4 refund.preview", SpanKind.STEP)) {
            try (Tracer.Scope tool = rig.tracer().span("tool refund.preview", SpanKind.TOOL)) {
                tool.integrity(false, "业务试算未通过:金额超出实付或为 0", IntegrityGrade.CAUGHT);
            }
        }
        rig.tracer().finish(trace, Outcome.FAILED);

        Trace saved = rig.store().find(trace.traceId()).orElseThrow();
        // 纯分数排序里它排第四;清单只有 3 格,但它是唯一能肉眼核对的证据,不能被挤掉
        assertThat(engine.rank(saved).get(3).name()).isEqualTo("tool refund.preview");
        assertThat(engine.triage(saved, 3).suspects())
                .extracting(Suspect::name).contains("tool refund.preview");
        assertThat(engine.triage(saved, 3).suspects().get(0).decisionCode()).isEqualTo("D05");
    }

    @Test
    @DisplayName("决策点的风险权重排序:参数绑定最高,结束条件最低")
    void decisionWeights() {
        assertThat(DecisionPoint.D05_ARG_BIND.weight()).isGreaterThan(DecisionPoint.D01_INTENT.weight());
        assertThat(DecisionPoint.D18_IDEMPOTENCY.weight()).isGreaterThan(DecisionPoint.D11_STEP_DEPENDENCY.weight());
        assertThat(DecisionPoint.D20_STOP.weight()).isLessThan(1.0);
        assertThat(DecisionPoint.values()).hasSize(20);
    }

    @Test
    @DisplayName("属性键常量存在,避免拼错 key")
    void attrKeys() {
        assertThat(AttrKeys.TOOL_NAME).isEqualTo("tool.name");
        assertThat(AttrKeys.WRITE_ECHO_MATCH).isEqualTo("write.echo.match");
    }
}
