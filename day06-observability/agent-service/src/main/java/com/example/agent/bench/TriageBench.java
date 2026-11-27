package com.example.agent.bench;

import com.example.agent.config.AgentProperties;
import com.example.agent.fault.FaultInjector;
import com.example.agent.fault.Scenario;
import com.example.agent.metrics.RunMetrics;
import com.example.agent.pipeline.AgentPipeline;
import com.example.agent.pipeline.RunResult;
import com.example.agent.pipeline.SlotBinder;
import com.example.agent.tool.CircuitBreaker;
import com.example.agent.tool.DemoTools;
import com.example.o11y.clock.VirtualClock;
import com.example.o11y.redact.Redactor;
import com.example.o11y.sample.TailSampler;
import com.example.o11y.span.Span;
import com.example.o11y.span.SpanKind;
import com.example.o11y.span.SpanStatus;
import com.example.o11y.span.Trace;
import com.example.o11y.store.InMemoryTraceStore;
import com.example.o11y.store.TraceStore;
import com.example.o11y.trace.Tracer;
import com.example.o11y.triage.Suspect;
import com.example.o11y.triage.TriageEngine;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 嫌疑排序评测:注入确定性的故障,看排序能不能把真正的埋点排进前三。
 *
 * <p>评测设计上的三个关键选择:</p>
 * <p>1、每条样本只有一个埋点,标准答案唯一,不存在"两个都是根因"的争论;</p>
 * <p>2、排序算法拿不到 {@code RunContext},它只能看 Span 和台账 —— 否则就是作弊;</p>
 * <p>3、用虚拟时钟,400ms 超时和退避等待都不花真实时间,200 条样本秒级跑完,
 *       而且两次跑出来的数字完全一致。</p>
 */
@Component
public class TriageBench {

    private static final Scenario[] SCENARIOS = {
            Scenario.SLOW_TOOL,
            Scenario.RETRY_STORM,
            Scenario.SILENT_FAILURE,
            Scenario.COST_BLOWUP,
            Scenario.ARG_DRIFT
    };

    private static final String SLOW_Q = "帮我把订单 ORD-100234 的 299 元退款处理一下,不想要了";
    private static final String FLAKY_Q = "查一下 SKU-201 还有库存吗";
    private static final String SILENT_Q = "订单 ORD-100236 退款 128 元,质量问题";
    private static final String COST_Q = "退款政策是怎么规定的,多久能到账";
    private static final String DRIFT_Q = "订单 ORD-100237 要退款";

    private final AgentProperties props;
    private final TriageEngine triage;

    public TriageBench(AgentProperties props, TriageEngine triage) {
        this.props = props;
        this.triage = triage;
    }

    private record Row(Scenario scenario, int rank, int spans, int decisions, double topScore,
                       boolean silent, double retryAmp, double ms,
                       boolean firstErrorHit, boolean slowestHit, boolean costliestHit) {
    }

    public BenchReport run(int incidents, long seed) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, props.getParallelism()));
        List<Row> rows = new ArrayList<>(incidents);
        try {
            for (int i = 0; i < incidents; i++) {
                Scenario scenario = SCENARIOS[i % SCENARIOS.length];
                rows.add(runOne(scenario, seed, i, pool));
            }
        } finally {
            pool.shutdownNow();
        }
        return aggregate(incidents, seed, rows);
    }

    private Row runOne(Scenario scenario, long seed, int index, ExecutorService pool) {
        String taskId = "B-" + scenario.name() + "-" + index;
        String question = questionOf(scenario);

        VirtualClock clock = new VirtualClock();
        TraceStore store = new InMemoryTraceStore(8);
        Tracer tracer = new Tracer(clock, new Redactor(), new TailSampler(1.0, 0.0), store);
        // 每次样本都用全新的注册表与熔断器:上一轮的幂等键、熔断状态不能漏到下一轮
        DemoTools demoTools = new DemoTools();
        AgentPipeline pipeline = new AgentPipeline(tracer, demoTools.registry(), new SlotBinder(),
                new FaultInjector(seed), props, triage, RunMetrics.noop(),
                new CircuitBreaker(clock, 5, 3000), pool);

        RunResult result = pipeline.run(question, scenario, "bench-user", taskId);
        Trace trace = store.find(result.traceId()).orElseThrow();
        List<Span> spans = trace.spans();

        // 命中判定按"埋点所属的 Span 名"算,而不是按 spanId。
        // 一次工具调用重试 3 次会生成 3 个同名 Span(每次尝试独立计时),
        // 用 spanId 判定会把"定位到正确的工具、但不是正确的某一次尝试"算成失败,
        // 这不是排障意义上的漏检,只是度量口径错了。
        String culpritName = spans.stream()
                .filter(s -> s.spanId().equals(result.culpritSpanId()))
                .map(Span::name).findFirst().orElse("");
        int rank = 0;
        for (int i = 0; i < result.triage().suspects().size(); i++) {
            if (result.triage().suspects().get(i).name().equals(culpritName)) {
                rank = i + 1;
                break;
            }
        }
        double topScore = result.triage().suspects().isEmpty() ? 0 : result.triage().suspects().get(0).score();
        boolean silent = trace.outcome() == com.example.o11y.span.Outcome.SUCCESS
                && spans.stream().anyMatch(s -> !s.integrityOk());

        String firstErrorName = spans.stream()
                .filter(s -> s.kind() != SpanKind.RUN && s.status() == SpanStatus.ERROR)
                .map(Span::name).findFirst().orElse(null);
        String slowestName = spans.stream().filter(s -> s.kind() != SpanKind.RUN)
                .max((a, b) -> Long.compare(a.durationNanos(), b.durationNanos()))
                .map(Span::name).orElse(null);
        String costliestName = spans.stream().filter(s -> s.kind() != SpanKind.RUN)
                .max((a, b) -> Long.compare(a.tokensIn() + a.tokensOut(), b.tokensIn() + b.tokensOut()))
                .map(Span::name).orElse(null);

        return new Row(scenario, rank, spans.size(), trace.decisions().size(),
                round3(topScore), silent, trace.retryAmplification(), round1(trace.totalMs()),
                culpritName.equals(firstErrorName),
                culpritName.equals(slowestName),
                culpritName.equals(costliestName));
    }

    private BenchReport aggregate(int incidents, long seed, List<Row> rows) {
        List<BenchReport.FamilyRow> families = new ArrayList<>();
        for (Scenario scenario : SCENARIOS) {
            List<Row> subset = rows.stream().filter(r -> r.scenario() == scenario).toList();
            if (subset.isEmpty()) {
                continue;
            }
            int hit1 = (int) subset.stream().filter(r -> r.rank() == 1).count();
            int hit3 = (int) subset.stream().filter(r -> r.rank() >= 1 && r.rank() <= 3).count();
            double mrr = subset.stream().mapToDouble(r -> r.rank() == 0 ? 0 : 1.0 / r.rank()).average().orElse(0);
            double avgRank = subset.stream().filter(r -> r.rank() > 0)
                    .mapToInt(Row::rank).average().orElse(0);
            families.add(new BenchReport.FamilyRow(
                    scenario.name(), scenario.label(), subset.size(),
                    ratio(hit1, subset.size()), ratio(hit3, subset.size()), round3(mrr), round1(avgRank),
                    round1(subset.stream().mapToInt(Row::spans).average().orElse(0)),
                    round1(subset.stream().mapToInt(Row::decisions).average().orElse(0)),
                    round3(subset.stream().mapToDouble(Row::topScore).average().orElse(0)),
                    (int) subset.stream().filter(r -> r.rank() == 0).count()));
        }

        int valid = (int) rows.stream().filter(r -> r.rank() > 0).count();
        int hit1 = (int) rows.stream().filter(r -> r.rank() == 1).count();
        int hit3 = (int) rows.stream().filter(r -> r.rank() >= 1 && r.rank() <= 3).count();
        double mrr = rows.stream().mapToDouble(r -> r.rank() == 0 ? 0 : 1.0 / r.rank()).average().orElse(0);

        BenchReport.Overall overall = new BenchReport.Overall(
                ratio(hit1, rows.size()), ratio(hit3, rows.size()), round3(mrr),
                round1(rows.stream().filter(r -> r.rank() > 0).mapToInt(Row::rank).average().orElse(0)),
                round1(rows.stream().mapToInt(Row::spans).average().orElse(0)),
                round1(rows.stream().mapToInt(Row::decisions).average().orElse(0)),
                round1(rows.stream().mapToDouble(Row::ms).average().orElse(0)),
                (int) rows.stream().filter(Row::silent).count(),
                round3(rows.stream().mapToDouble(Row::retryAmp).average().orElse(0)));

        List<BenchReport.Baseline> baselines = List.of(
                new BenchReport.Baseline("第一个报错的 Span", "最直觉的做法:从树上找第一个 ERROR",
                        ratio((int) rows.stream().filter(Row::firstErrorHit).count(), rows.size()),
                        ratio((int) rows.stream().filter(Row::firstErrorHit).count(), rows.size()),
                        round3(rows.stream().mapToDouble(r -> r.firstErrorHit() ? 1.0 : 0.0).average().orElse(0))),
                new BenchReport.Baseline("最慢的 Span", "只看耗时,忽略错误与成本",
                        ratio((int) rows.stream().filter(Row::slowestHit).count(), rows.size()),
                        ratio((int) rows.stream().filter(Row::slowestHit).count(), rows.size()),
                        round3(rows.stream().mapToDouble(r -> r.slowestHit() ? 1.0 : 0.0).average().orElse(0))),
                new BenchReport.Baseline("最贵的 Span", "只看 token",
                        ratio((int) rows.stream().filter(Row::costliestHit).count(), rows.size()),
                        ratio((int) rows.stream().filter(Row::costliestHit).count(), rows.size()),
                        round3(rows.stream().mapToDouble(r -> r.costliestHit() ? 1.0 : 0.0).average().orElse(0))));

        String note = String.format("共 %d 条样本,有效埋点 %d 条;平均 %.1f 个 Span、%.1f 条决策,"
                        + "排序只需看前 3 条,阅读量下降约 %.0f%%",
                rows.size(), valid,
                overall.avgSpans(), overall.avgDecisions(),
                overall.avgSpans() <= 0 ? 0 : (1 - 3.0 / overall.avgSpans()) * 100);

        return new BenchReport(incidents, seed, Instant.now().toString(), families, overall, baselines, note);
    }

    private String questionOf(Scenario scenario) {
        return switch (scenario) {
            case SLOW_TOOL -> SLOW_Q;
            case RETRY_STORM -> FLAKY_Q;
            case SILENT_FAILURE -> SILENT_Q;
            case COST_BLOWUP -> COST_Q;
            case ARG_DRIFT -> DRIFT_Q;
            case NORMAL -> SLOW_Q;
        };
    }

    private static double ratio(int hit, int total) {
        return total == 0 ? 0 : Math.round(hit * 10000.0 / total) / 10000.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 供接口返回"每条样本的判定"用,方便前端画散点。 */
    public List<Suspect> suspectsOf(RunResult result) {
        return result.triage().suspects();
    }
}
