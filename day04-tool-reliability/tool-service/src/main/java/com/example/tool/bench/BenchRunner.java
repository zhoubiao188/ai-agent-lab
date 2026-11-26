package com.example.tool.bench;

import com.example.tool.agent.AgentOrchestrator;
import com.example.tool.agent.EventSink;
import com.example.tool.agent.TaskOutcome;
import com.example.tool.config.ToolProperties;
import com.example.tool.exec.ResilienceRegistry;
import com.example.tool.exec.RunPolicy;
import com.example.tool.exec.ToolMetrics;
import com.example.tool.fault.FaultInjector;
import com.example.tool.tools.LatencyGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对照实验:同一个编排器、同一批任务、同一个故障序列,只换可靠性开关。
 *
 * <p>评测最容易糊弄自己的地方是"换个 seed 再跑一次,数字就好看了"。
 * 这里的做法是四组配置共用 seed,而且故障注入是 (seed, 工具, 调用ID, 尝试次数) 的纯函数,
 * 所以 L0 和 L3 面对的是同一批故障 —— 差别只可能来自机制本身。</p>
 *
 * <p>另一个刻意的设计:延迟只统计"成功的任务"。失败的任务往往几毫秒就返回了,
 * 把它算进平均延迟,会得出"越不靠谱越快"这种荒唐结论。</p>
 */
@Component
public class BenchRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchRunner.class);

    private final AgentOrchestrator orchestrator;
    private final FaultInjector faults;
    private final ResilienceRegistry resilience;
    private final LatencyGate latency;
    private final ToolProperties props;

    public BenchRunner(AgentOrchestrator orchestrator, FaultInjector faults,
                       ResilienceRegistry resilience, LatencyGate latency, ToolProperties props) {
        this.orchestrator = orchestrator;
        this.faults = faults;
        this.resilience = resilience;
        this.latency = latency;
        this.props = props;
    }

    public BenchReport run(int tasks, long seed, double scale) {
        long t0 = System.currentTimeMillis();
        latency.scale(scale);
        List<BenchReport.Row> rows = new ArrayList<>();
        try {
            for (RunPolicy policy : List.of(RunPolicy.BASELINE, RunPolicy.RETRY,
                    RunPolicy.RETRY_REPAIR, RunPolicy.FULL)) {
                rows.add(runOne(policy, tasks, seed));
            }
        } finally {
            latency.reset();
            faults.configure(props.fault().seed(), props.fault().enabled());
            resilience.reset();
        }
        long total = System.currentTimeMillis() - t0;
        log.info("评测完成: {} 任务 × {} 配置,耗时 {}ms", tasks, rows.size(), total);
        return new BenchReport(seed, tasks, scale, total, rows);
    }

    private BenchReport.Row runOne(RunPolicy policy, int tasks, long seed) {
        resilience.reset();
        faults.clearForced();
        faults.configure(seed, true);

        int success = 0;
        int degraded = 0;
        int failed = 0;
        int calls = 0;
        List<Long> okWalls = new ArrayList<>();
        Map<String, Integer> failureKinds = new TreeMap<>();

        for (int i = 0; i < tasks; i++) {
            String task = "处理订单 A" + (10001 + i) + " 的退款申请,并跟进物流异常";
            TaskOutcome outcome = orchestrator.run(task,
                    "bench-" + policy.name() + "-" + i, "t" + i, policy, EventSink.NOOP);
            calls += outcome.callCount();
            switch (outcome.status()) {
                case SUCCESS -> {
                    success++;
                    okWalls.add(outcome.wallMs());
                }
                case DEGRADED -> {
                    degraded++;
                    okWalls.add(outcome.wallMs());
                }
                case FAILED -> {
                    failed++;
                    outcome.results().stream().filter(r -> !r.ok())
                            .forEach(r -> failureKinds.merge(r.errorKind(), 1, Integer::sum));
                }
            }
        }

        long avg = okWalls.isEmpty() ? 0
                : (long) okWalls.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = percentile(okWalls, 0.95);

        int retries = 0;
        int repairs = 0;
        int fallbacks = 0;
        long circuitRejects = 0;
        for (ToolMetrics.Snapshot s : resilience.snapshot().values()) {
            retries += (int) s.retries();
            repairs += (int) s.repairs();
            fallbacks += (int) s.fallbacks();
            circuitRejects += s.circuitRejects();
        }
        return new BenchReport.Row(policy.name(), success, degraded, failed,
                round((double) success / tasks), round((double) degraded / tasks),
                avg, p95, calls, retries, repairs, fallbacks, circuitRejects, failureKinds);
    }

    private long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
