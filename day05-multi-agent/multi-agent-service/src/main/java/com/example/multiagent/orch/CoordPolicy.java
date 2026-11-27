package com.example.multiagent.orch;

/**
 * 协调层的四组配置。同一个编排器跑不同策略,对比出来的才是"机制带来的差异",
 * 而不是"四份实现之间的差异"。
 *
 * <p>注意 {@code patch} 和 {@code replan} 是两个不同的修复动作:补步便宜且确定,
 * 重规划贵且要模型重来。把它们拆开,才能看清"哪一类错误值得花多少钱去修"。</p>
 */
public record CoordPolicy(
        String name,
        boolean validate,
        boolean coverage,
        boolean patch,
        boolean critic,
        boolean replan
) {

    /** 什么都不做:计划写了就执行,全部返回码 200 就算完成 */
    public static final CoordPolicy NONE =
            new CoordPolicy("C0-无契约", false, false, false, false, false);

    /** 只做静态校验:拦截结构错误,但拦下来就结束 */
    public static final CoordPolicy VALIDATE =
            new CoordPolicy("C1-静态校验", true, false, false, false, false);

    /** 加覆盖核对 + 机械补步:漏项能自己补上 */
    public static final CoordPolicy COVERAGE =
            new CoordPolicy("C2-覆盖补步", true, true, true, false, false);

    /** 全量:校验 + 覆盖 + 事后复核 + 一次重规划 */
    public static final CoordPolicy FULL =
            new CoordPolicy("C3-复核重规划", true, true, true, true, true);

    public static CoordPolicy of(String name, boolean validate, boolean coverage,
                                 boolean patch, boolean critic, boolean replan) {
        return new CoordPolicy(name, validate, coverage, patch, critic, replan);
    }
}
