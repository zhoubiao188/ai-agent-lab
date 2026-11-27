package com.example.multiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 协调层的旋钮。工具层的旋钮还留在 {@link ToolProperties} 里 ——
 * 两层分开配,是因为它们的调参依据完全不同:工具层看 P99 延迟和上游 SLA,
 * 协调层看"计划错了之后多久能发现"。
 */
@ConfigurationProperties(prefix = "coord")
public record CoordProperties(
        /** 一份计划最多几步。超过就是拆碎了,延迟和失败概率都是乘出来的 */
        @DefaultValue("8") int maxSteps,
        /** 重规划次数上限。1 次是有意的:第二次重规划改对的概率极低,但延迟翻倍 */
        @DefaultValue("1") int maxReplans,
        /** 演示用的计划缺陷注入率(模拟模型拆错的概率),评测里由任务固定指定 */
        @DefaultValue("0.5") double flawRate,
        /** 一次任务(含重规划)的墙钟预算 */
        @DefaultValue("15000") long budgetMs,
        Bench bench
) {

    public record Bench(
            @DefaultValue("20261127") long seed
    ) {
    }
}
