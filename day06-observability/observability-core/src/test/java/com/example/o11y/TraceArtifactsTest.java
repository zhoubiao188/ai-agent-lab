package com.example.o11y;

import com.example.o11y.clock.VirtualClock;
import com.example.o11y.decision.Basis;
import com.example.o11y.decision.Decision;
import com.example.o11y.decision.DecisionPoint;
import com.example.o11y.export.JsonlExporter;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.Outcome;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.Trace;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.trace.Tracer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceArtifactsTest {

    private Trace sampleTrace(InMemoryTraceStore store, Tracer tracer, VirtualClock clock) {
        Trace trace = tracer.begin("t-art", "NORMAL", "退款", "u1");
        try (Tracer.DecisionScope d = tracer.decision(DecisionPoint.D02_SLOT_AUDIT)) {
            d.record(Decision.at(DecisionPoint.D02_SLOT_AUDIT)
                    .chosen("槽位齐全").basis(Basis.RULE).confidence(0.9)
                    .alternative("槽位齐全", 0.94, "全部来自原话")
                    .alternative("默认值兜底", 0.3, "缺参数")
                    .attr("slot.count", 3)
                    .build());
        }
        try (Tracer.Scope s = tracer.span("tool order.get", SpanKind.TOOL)) {
            s.tag("tool.name", "order.get").attempts(2);
            clock.sleep(50);
        }
        tracer.finish(trace, Outcome.SUCCESS);
        return store.find(trace.traceId()).orElseThrow();
    }

    @Test
    @DisplayName("JSONL 导出:一行一个 Span,加一行一个决策,全部是合法 JSON")
    void jsonlIsLineDelimited() throws Exception {
        VirtualClock clock = new VirtualClock();
        InMemoryTraceStore store = new InMemoryTraceStore(8);
        Tracer tracer = new Tracer(clock, new Redactor(), new TailSampler(1, 0), store);
        Trace trace = sampleTrace(store, tracer, clock);

        String jsonl = new JsonlExporter().export(trace);
        List<String> lines = jsonl.strip().lines().toList();
        assertThat(lines).hasSize(trace.spans().size() + trace.decisions().size());

        ObjectMapper mapper = new ObjectMapper();
        Set<String> kinds = new HashSet<>();
        for (String line : lines) {
            JsonNode node = mapper.readTree(line);
            assertThat(node.get("traceId").asText()).isEqualTo(trace.traceId());
            if (node.has("type")) {
                kinds.add("decision");
            } else {
                kinds.add("span");
                assertThat(node.get("name").asText()).isNotBlank();
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("span", "decision");
    }

    @Test
    @DisplayName("内存存储:超容量淘汰最老的一条,并且索引同步删除")
    void storeEvictsAndDropsIndex() {
        InMemoryTraceStore store = new InMemoryTraceStore(3);
        VirtualClock clock = new VirtualClock();
        Tracer tracer = new Tracer(clock, new Redactor(), new TailSampler(1, 0), store);

        String first = null;
        for (int i = 0; i < 5; i++) {
            Trace t = tracer.begin("t" + i, "NORMAL", "q", "u");
            tracer.finish(t, Outcome.SUCCESS);
            if (i == 0) {
                first = t.traceId();
            }
        }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.find(first)).isEmpty();
        assertThat(store.recent(10)).hasSize(3);
        // 淘汰后最近一条应该是最新写入的
        assertThat(store.recent(1).get(0).taskId()).isEqualTo("t4");
    }

    @Test
    @DisplayName("决策差值:排除被选中项后算相对差距,而不是恒为 0")
    void marginExcludesChosen() {
        Decision close = Decision.at(DecisionPoint.D04_TOOL_SELECT)
                .chosen("A").confidence(0.6)
                .alternative("A", 0.90, "首选")
                .alternative("B", 0.85, "次优")
                .build();
        Decision far = Decision.at(DecisionPoint.D04_TOOL_SELECT)
                .chosen("A").confidence(0.95)
                .alternative("A", 0.90, "首选")
                .alternative("B", 0.20, "差很远")
                .build();
        Decision single = Decision.at(DecisionPoint.D01_INTENT)
                .chosen("退款").confidence(1.0)
                .build();

        assertThat(close.margin()).isCloseTo(0.0556, org.assertj.core.data.Offset.offset(0.001));
        assertThat(far.margin()).isCloseTo(0.7778, org.assertj.core.data.Offset.offset(0.001));
        assertThat(single.margin()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("决策属性在 record 之后仍然保留(构造器不能漏拷 attrs)")
    void decisionKeepsAttrs() {
        VirtualClock clock = new VirtualClock();
        InMemoryTraceStore store = new InMemoryTraceStore(8);
        Tracer tracer = new Tracer(clock, new Redactor(), new TailSampler(1, 0), store);
        Trace trace = sampleTrace(store, tracer, clock);

        Decision d = trace.decisions().get(0);
        assertThat(d.attrs()).containsEntry("slot.count", 3);
        assertThat(d.spanId()).isNotBlank();
        assertThat(d.seq()).isEqualTo(1);
        assertThat(d.margin()).isBetween(0.0, 1.0);
    }
}
