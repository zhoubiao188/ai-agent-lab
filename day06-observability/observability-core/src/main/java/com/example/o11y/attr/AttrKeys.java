package com.example.o11y.attr;

/**
 * 属性名常量表。
 *
 * <p>属性名看起来是小事,实际是聚合能不能做的分水岭。{@code tool}、{@code toolName}、
 * {@code tool_name} 三种写法混在一个系统里,想看"每个工具的失败率"就得写三段兼容代码。
 * 这张表就是这个项目的唯一命名标准,加属性前先来这里加常量。</p>
 *
 * <p>命名遵循 OpenTelemetry 的点分层级习惯:领域.对象.字段。</p>
 */
public final class AttrKeys {

    private AttrKeys() {
    }

    // 运行级
    public static final String AGENT_TASK_ID = "agent.task.id";
    public static final String AGENT_SCENARIO = "agent.scenario";
    public static final String AGENT_OUTCOME = "agent.outcome";
    public static final String AGENT_TRUST = "agent.trust";
    public static final String AGENT_STEP_COUNT = "agent.step.count";

    // 步骤
    public static final String STEP_INDEX = "step.index";
    public static final String STEP_TOOL = "step.tool";
    public static final String STEP_DEPENDS_ON = "step.dependsOn";

    // 工具
    public static final String TOOL_NAME = "tool.name";
    public static final String TOOL_WRITE = "tool.write";
    public static final String TOOL_ATTEMPT = "tool.attempt";
    public static final String TOOL_ARG_HASH = "tool.arg.hash";
    public static final String TOOL_ARG_SUMMARY = "tool.arg.summary";
    public static final String TOOL_DEADLINE_MS = "tool.deadline.ms";
    public static final String TOOL_RESULT_SUMMARY = "tool.result.summary";
    public static final String TOOL_RETRYABLE = "tool.retryable";

    // 模型
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_TOKENS_IN = "llm.tokens.in";
    public static final String LLM_TOKENS_OUT = "llm.tokens.out";
    public static final String LLM_COST_MICROS = "llm.cost.micros";
    public static final String LLM_STOP_REASON = "llm.stop.reason";

    // 决策
    public static final String DECISION_CODE = "decision.code";
    public static final String DECISION_TITLE = "decision.title";
    public static final String DECISION_CHOSEN = "decision.chosen";
    public static final String DECISION_BASIS = "decision.basis";
    public static final String DECISION_CONFIDENCE = "decision.confidence";
    public static final String DECISION_MARGIN = "decision.margin";
    public static final String DECISION_ALTERNATIVES = "decision.alternatives";

    // 写入与完整性
    public static final String WRITE_IDEMPOTENCY_KEY = "write.idempotency.key";
    public static final String WRITE_ECHO_MATCH = "write.echo.match";
    public static final String WRITE_DUPLICATE_BLOCKED = "write.duplicate.blocked";

    // 错误与重试
    public static final String ERROR_KIND = "error.kind";
    public static final String RETRY_DELAY_MS = "retry.delay.ms";
    public static final String RETRY_REASON = "retry.reason";

    // 脱敏
    public static final String REDACTION_COUNT = "redaction.count";
}
