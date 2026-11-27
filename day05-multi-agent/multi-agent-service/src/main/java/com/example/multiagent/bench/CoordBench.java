package com.example.multiagent.bench;

import com.example.multiagent.agent.EventSink;
import com.example.multiagent.config.CoordProperties;
import com.example.multiagent.orch.CoordPolicy;
import com.example.multiagent.orch.Coordinator;
import com.example.multiagent.orch.TaskResult;
import com.example.multiagent.tasks.TaskCatalog;
import com.example.multiagent.tasks.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 四组配置的对照实验。
 *
 * <p>关键约束:同一批任务、同一份缺陷、同一个顺序,只有"防线层数"这一个变量在变。
 * 否则跑出来的差异说不清是谁带来的 —— 这是评测最容易自欺的地方。</p>
 */
@Component
public class CoordBench {

    private static final Logger log = LoggerFactory.getLogger(CoordBench.class);

    private final Coordinator coordinator;
    private final TaskCatalog catalog;
    private final CoordProperties props;

    public CoordBench(Coordinator coordinator, TaskCatalog catalog, CoordProperties props) {
        this.coordinator = coordinator;
        this.catalog = catalog;
        this.props = props;
    }

    public List<CoordPolicy> policies() {
        return List.of(CoordPolicy.NONE, CoordPolicy.VALIDATE, CoordPolicy.COVERAGE, CoordPolicy.FULL);
    }

    public BenchReport run() {
        return run(policies(), catalog.all());
    }

    public BenchReport run(List<CoordPolicy> policies, List<TaskSpec> tasks) {
        List<BenchReport.Row> rows = new ArrayList<>();
        Map<String, BenchReport.TaskDetail> details = new LinkedHashMap<>();
        tasks.forEach(t -> details.put(t.id(), new BenchReport.TaskDetail(t.id(), t.request(),
                t.hasFlaw() ? t.forcedFlaw().name() : "CLEAN", new LinkedHashMap<>())));

        for (CoordPolicy policy : policies) {
            int success = 0;
            int falseSuccess = 0;
            int blocked = 0;
            int replans = 0;
            long totalMs = 0;
            long totalSteps = 0;
            List<Long> durations = new ArrayList<>();
            Map<String, Integer> failureKinds = new TreeMap<>();

            for (TaskSpec task : tasks) {
                TaskResult r = coordinator.run(task, policy, EventSink.NOOP);
                if (r.success()) {
                    success++;
                }
                if (r.falseSuccess()) {
                    falseSuccess++;
                }
                if (r.executedSteps() == 0) {
                    blocked++;
                }
                replans += r.replans();
                totalMs += r.durationMs();
                totalSteps += r.executedSteps();
                durations.add(r.durationMs());
                r.reasons().forEach(reason -> {
                    String kind = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
                    failureKinds.merge(kind, 1, Integer::sum);
                });
                details.get(task.id()).byPolicy().put(policy.name(),
                        r.success() ? "OK" : String.join(" | ", r.reasons()));
                log.debug("{} {} → {}", task.id(), policy.name(), r.success());
            }

            int n = Math.max(1, tasks.size());
            rows.add(new BenchReport.Row(policy.name(), tasks.size(), success, falseSuccess, blocked,
                    round((double) success / n), round((double) totalSteps / n),
                    totalMs / n, p95(durations), replans, failureKinds));
        }
        return new BenchReport(props.bench().seed(), tasks.size(), rows, List.copyOf(details.values()));
    }

    private long p95(List<Long> durations) {
        if (durations.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(durations);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
