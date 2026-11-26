package com.example.tool.bench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把工单接口改造成幂等之后,再跑一遍同样的实验。
 *
 * <p>这一个用例回答的是机制救不了的那部分:写接口如果不是幂等的,
 * 它一旦失败,重试策略连碰都不敢碰,成功率就永远卡在那里。
 * 机制的上限由业务语义决定,改代码之前先改语义。</p>
 */
@SpringBootTest(properties = "tool.ticket-idempotent=true")
class IdempotentWriteBenchTest {

    @Autowired
    private BenchRunner runner;

    @Test
    void 写接口幂等化之后成功率上限被抬高() {
        BenchReport report = runner.run(120, 20261126L, 0.1);
        BenchRunnerTest.print(report);

        assertThat(report.rows()).hasSize(4);
        BenchReport.Row baseline = report.rows().get(0);
        BenchReport.Row full = report.rows().get(3);
        assertThat(full.successRate()).isGreaterThan(baseline.successRate());
    }
}
