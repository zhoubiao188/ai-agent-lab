package com.example.o11y.span;

/**
 * 完整性失败的两种性质。这个区分是排障里最容易被忽略、但影响结论方向最大的一件事。
 *
 * <p>{@link #CAUGHT} —— 护栏拦下了。试算接口拒绝、Schema 校验不通过、权限前置检查失败。
 * 这类 Span 本身不改状态,它报出来的是"上游给我的东西不对",事故现场不在这里,
 * 而在**把它生成出来的那个决策**里。把它当第一嫌疑人,值班的人会去读一个证明不了因果的位置。</p>
 *
 * <p>{@link #LANDED} —— 坏结果已经落地。写入回显与入参不一致、接口返回成功但内容错、
 * 承诺的字段缺失。这类 Span 就是事故现场,分数必须给满。</p>
 *
 * <p>反过来说:如果一次运行里只有 CAUGHT 级的失败而且没有任何 ERROR,
 * 那说明护栏工作正常、损失被挡住了 —— 但它依然是一次需要复盘的运行,
 * 因为同一段逻辑换个不带护栏的下游,坏结果就会落地。</p>
 */
public enum IntegrityGrade {

    /** 坏结果已经落地,这个 Span 就是事故现场。 */
    LANDED("已落地", 1.0),

    /** 护栏拦下,坏结果没有落地,真正的成因在上游。 */
    CAUGHT("护栏拦下", 0.5);

    private final String label;
    private final double severity;

    IntegrityGrade(String label, double severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    /** 完整性信号的基础强度。护栏拦下的证据,信息量只有落地缺陷的一半。 */
    public double severity() {
        return severity;
    }
}
