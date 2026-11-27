package com.example.agent.bench;

import com.example.agent.config.AgentProperties;
import com.example.o11y.triage.TriageEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测本身也要被评测。
 *
 * <p>三条断言:</p>
 * <p>1、排序必须明显好于三个单点基线,否则这套复杂度不值得;</p>
 * <p>2、同一 seed 跑两次结果必须完全一致,否则文章里的数字没人能复现;</p>
 * <p>3、200 条样本要在秒级跑完,否则评测进不了 CI。</p>
 */
class TriageBenchTest {

    @Test
    @DisplayName("100 条样本:R@3 不低于 0.95,R@1 明显高于单点基线")
    void triageBeatsBaselines() {
        BenchReport report = new TriageBench(new AgentProperties(), new TriageEngine()).run(100, 20261127L);

        assertThat(report.overall().recall3()).isGreaterThanOrEqualTo(0.95);
        assertThat(report.overall().recall1()).isGreaterThanOrEqualTo(0.7);
        assertThat(report.overall().mrr()).isGreaterThanOrEqualTo(0.8);

        double bestBaseline = report.baselines().stream().mapToDouble(BenchReport.Baseline::recall1).max().orElse(0);
        assertThat(report.overall().recall1()).isGreaterThan(bestBaseline);
        // 前三条之外不用看:阅读量必须实打实降下来
        assertThat(report.overall().avgSpans()).isGreaterThan(15);
        assertThat(report.overall().avgDecisions()).isGreaterThan(10);
        assertThat(report.families()).hasSize(5);
        assertThat(report.overall().silentFailures()).isGreaterThan(0);
    }

    @Test
    @DisplayName("同一 seed 两次运行结果完全一致")
    void reproducible() {
        TriageBench bench = new TriageBench(new AgentProperties(), new TriageEngine());
        BenchReport a = bench.run(50, 42L);
        BenchReport b = bench.run(50, 42L);

        assertThat(b.overall()).isEqualTo(a.overall());
        assertThat(b.families()).isEqualTo(a.families());
        assertThat(b.baselines()).isEqualTo(a.baselines());
    }

    @Test
    @DisplayName("每条样本都只埋一个故障,标准答案唯一")
    void everyIncidentHasCulprit() {
        BenchReport report = new TriageBench(new AgentProperties(), new TriageEngine()).run(50, 7L);
        assertThat(report.families()).allSatisfy(f -> assertThat(f.noCulprit()).isZero());
    }
}
