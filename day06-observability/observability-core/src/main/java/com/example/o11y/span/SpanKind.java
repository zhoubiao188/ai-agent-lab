package com.example.o11y.span;

/** Span 的语义类型。类型决定了页面上画什么颜色、聚合时怎么算指标。 */
public enum SpanKind {

    /** 一次完整的 Agent 运行(根 Span)。 */
    RUN("运行", true),

    /** 计划中的一步。 */
    STEP("步骤", false),

    /** 模型调用(含 token 与费用)。 */
    LLM("模型", false),

    /** 工具调用(外部依赖,延迟与错误的主要来源)。 */
    TOOL("工具", false),

    /** 一次重试尝试,挂在 TOOL 下面。 */
    RETRY("重试", false),

    /** 一个决策点,几乎没有耗时,但它是"为什么这么做"的唯一证据。 */
    DECISION("决策", false),

    /** 断言 / 自检。 */
    VALIDATE("校验", false),

    /** 写操作。需要额外记录幂等键与回显校验结果。 */
    WRITE("写入", false),

    /** 内部处理(参数绑定、渲染、压缩等)。 */
    INTERNAL("内部", false);

    private final String label;
    private final boolean root;

    SpanKind(String label, boolean root) {
        this.label = label;
        this.root = root;
    }

    public String label() {
        return label;
    }

    public boolean root() {
        return root;
    }
}
