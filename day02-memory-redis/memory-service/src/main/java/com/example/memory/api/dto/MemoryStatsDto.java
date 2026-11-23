package com.example.memory.api.dto;

/**
 * 记忆子系统运行状态。运维看板的最小集合:
 * 存储类型、会话总数、活跃锁、摘要熔断状态、预算与 TTL。
 */
public record MemoryStatsDto(
        String store,
        long conversations,
        int activeLocks,
        boolean summarizerBreakerOpen,
        int budgetTokens,
        long ttlSeconds
) {
}
