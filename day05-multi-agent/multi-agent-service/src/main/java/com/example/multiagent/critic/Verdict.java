package com.example.multiagent.critic;

/**
 * Critic 的结论。三种处置对应三种修复成本,这个映射是本文的核心结论之一:
 *
 * <pre>
 * PASS    → 直接交付
 * PATCH   → 机械补步(不用模型),便宜
 * REPLAN  → 必须重写计划(依赖是全局的),贵,且次数必须设上限
 * FAIL    → 修不了,如实告诉用户
 * </pre>
 */
public enum Verdict {
    PASS,
    PATCH,
    REPLAN,
    FAIL
}
