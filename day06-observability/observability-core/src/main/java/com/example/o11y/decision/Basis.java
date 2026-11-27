package com.example.o11y.decision;

/** 决策的依据类型。它比"选了什么"更重要:默认值兜底和模型判断,出错概率完全不同。 */
public enum Basis {

    /** 规则命中,确定性最高。 */
    RULE("规则"),
    /** 启发式(打分、阈值、相似度),可能卡在阈值边缘。 */
    HEURISTIC("启发式"),
    /** 模型输出。 */
    MODEL("模型"),
    /** 上一轮记忆。 */
    MEMORY("记忆"),
    /** 默认值兜底。线上事故高发区:它让流程"看起来成功"。 */
    DEFAULT("默认值"),
    /** 降级路径。 */
    FALLBACK("降级");

    private final String label;

    Basis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
