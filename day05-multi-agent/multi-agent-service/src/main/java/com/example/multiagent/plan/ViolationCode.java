package com.example.multiagent.plan;

/**
 * 计划可能犯的错。每一条都对应一种"执行阶段完全看不出来"的问题 ——
 * 这正是计划校验存在的理由:步骤全绿,任务全错。
 */
public enum ViolationCode {

    /** 两个步骤同 id,后一个会覆盖前一个 */
    DUPLICATE_ID("步骤 id 重复"),

    /** 调了一个没注册的工具 */
    UNKNOWN_TOOL("工具未注册"),

    /** 依赖了一个不存在的步骤 */
    DANGLING_DEPENDENCY("依赖的步骤不存在"),

    /** 环:A 依赖 B,B 依赖 A */
    CYCLE("依赖成环,计划不可执行"),

    /**
     * 参数里引用了某个步骤的产出,但没有把它写进 dependsOn。
     * 这是"顺序错"最典型的机器可读形态 —— 数据流断了,但每一步单看都合法。
     */
    MISSING_DEPENDENCY("参数引用了未声明的依赖"),

    /** 引用的字段,被引用的步骤根本没承诺产出 */
    UNPRODUCED_REFERENCE("引用了没人产出的字段"),

    /** 步骤数超过上限:拆得太碎 */
    TOO_MANY_STEPS("步骤数超过上限"),

    /** 同一工具 + 同一参数出现两次 */
    REDUNDANT_STEP("重复步骤"),

    /** 写操作的金额/单号是模型写死的字面量,不是上游算出来的 */
    UNGROUNDED_WRITE("写操作关键参数无法溯源");

    private final String label;

    ViolationCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
