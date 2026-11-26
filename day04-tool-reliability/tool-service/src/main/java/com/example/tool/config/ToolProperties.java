package com.example.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 可靠性相关的全部旋钮。
 *
 * <p>这些值不是拍脑袋定的,每一组都对应一个能观测的量:超时对应 P99 工具延迟,
 * 重试次数对应上游 SLA,熔断阈值对应"坏掉时多快能放弃"。全部可配,才能在线调。</p>
 */
@ConfigurationProperties(prefix = "tool")
public record ToolProperties(
        /** 单次工具调用的硬超时。必须小于上游自己的超时,否则你的超时永远不会触发 */
        @DefaultValue("400") long timeoutMs,
        /** 总尝试次数(含首次)。3 次是常见折中:再往上延迟涨得比成功率快 */
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("40") long backoffBaseMs,
        @DefaultValue("400") long backoffMaxMs,
        /** 抖动比例 0~1,避免所有重试在同一时刻打回上游形成二次雪崩 */
        @DefaultValue("0.5") double jitter,
        @DefaultValue("true") boolean retryOnTimeout,
        @DefaultValue("true") boolean retryOnRateLimit,
        @DefaultValue("true") boolean retryOnUpstream5xx,
        /** 写操作只有当工具声明 idempotent(带幂等键)时才允许重试 */
        @DefaultValue("true") boolean retryWrites,
        Breaker breaker,
        Parallel parallel,
        Agent agent,
        Fault fault,
        Bench bench,
        /** 演示/评测用的延迟倍率:压小它只是为了跑得快,不影响成功率 */
        @DefaultValue("1.0") double latencyScale,
        /**
         * 演示开关:工单接口是否已做幂等改造。
         * 打开它再跑一次评测,就能看到非幂等写接口对成功率上限的影响有多大。
         */
        @DefaultValue("false") boolean ticketIdempotent
) {

    public record Breaker(
            @DefaultValue("8000") long windowMs,
            @DefaultValue("20") int minCalls,
            /** 窗口内失败率超过这个值就打开 */
            @DefaultValue("0.5") double failureRate,
            @DefaultValue("1500") long openMs,
            @DefaultValue("1") int halfOpenProbes
    ) {
    }

    public record Parallel(
            @DefaultValue("8") int maxConcurrency,
            /** 关闭后退化成串行,用来做并行收益的对照 */
            @DefaultValue("true") boolean enabled
    ) {
    }

    public record Agent(
            /** 一次任务的墙钟预算,超了就不再发起新调用,直接收尾 */
            @DefaultValue("20000") long budgetMs,
            /** 参数校验失败后回灌模型让它重发的最大轮数 */
            @DefaultValue("1") int maxRepairRounds
    ) {
    }

    public record Fault(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20261126") long seed,
            @DefaultValue("0.03") double timeoutRate,
            @DefaultValue("0.02") double rateLimitRate,
            @DefaultValue("0.03") double upstreamRate,
            @DefaultValue("0.01") double businessRate,
            /** 参数污染比例:这是参数修复能力的评测来源 */
            @DefaultValue("0.12") double corruptRate
    ) {
    }

    public record Bench(
            @DefaultValue("120") int tasks,
            @DefaultValue("20261126") long seed
    ) {
    }
}
