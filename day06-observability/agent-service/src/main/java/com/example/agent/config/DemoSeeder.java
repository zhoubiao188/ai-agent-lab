package com.example.agent.config;

import com.example.agent.fault.Scenario;
import com.example.agent.metrics.RunMetrics;
import com.example.agent.pipeline.AgentPipeline;
import com.example.o11y.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时灌 6 条示例运行,让页面一打开就有东西可看。
 *
 * <p>线上不该有这个类。放在这里是为了"clone 下来就能看到三屏长什么样",
 * 否则读者要先自己点六次才能理解文章里的截图。</p>
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private record Seed(String question, Scenario scenario) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("帮我把订单 ORD-100234 的 299 元退款处理一下,不想要了", Scenario.NORMAL),
            new Seed("订单 ORD-100236 的物流到哪了", Scenario.SLOW_TOOL),
            new Seed("订单 ORD-100235 还有库存吗?查一下 SKU-201", Scenario.RETRY_STORM),
            new Seed("订单 ORD-100236 退款 128 元,质量问题", Scenario.SILENT_FAILURE),
            new Seed("退款政策是怎么规定的,多久能到账", Scenario.COST_BLOWUP),
            new Seed("订单 ORD-100237 要退款", Scenario.ARG_DRIFT));

    private final AgentPipeline pipeline;
    private final TraceStore store;

    public DemoSeeder(AgentPipeline pipeline, TraceStore store) {
        this.pipeline = pipeline;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.size() > 0) {
            return;
        }
        for (int i = 0; i < SEEDS.size(); i++) {
            Seed seed = SEEDS.get(i);
            try {
                var result = pipeline.run(seed.question(), seed.scenario(), "u-demo",
                        "D" + (i + 1) + "-" + seed.scenario().name().toLowerCase());
                log.info("seed {} -> {} outcome={} suspects={}", seed.scenario().label(),
                        result.traceId(), result.outcome(),
                        result.triage().suspects().stream().map(s -> s.name() + ":" + s.score()).toList());
            } catch (RuntimeException e) {
                log.warn("seed 失败 {}", seed.scenario(), e);
            }
        }
    }
}
