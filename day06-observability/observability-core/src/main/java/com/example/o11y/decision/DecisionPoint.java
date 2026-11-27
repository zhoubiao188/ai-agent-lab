package com.example.o11y.decision;

/**
 * Agent 一次运行里真正做判断的 20 个点。
 *
 * <p>为什么要把它做成枚举,而不是随手在代码里写 log:线上出问题时,值班的人先问的永远是
 * "它当时在纠结什么",而不是"它执行了什么"。这两件事不一样 —— 执行记录告诉你它做了什么,
 * 决策记录才告诉你它本可以做什么、为什么没做。前者查日志就有,后者只能提前埋。</p>
 *
 * <p>每个点都写了"出问题时长什么样"(symptom)。这句话是排障的索引:你从现象反查该点,
 * 比从 20 个点里逐个看快得多。</p>
 */
public enum DecisionPoint {

    D01_INTENT("D01", "意图解析", "这句话要办的是哪件事?", "答非所问、走错业务分支", 0.90),
    D02_SLOT_AUDIT("D02", "槽位盘点", "关键参数齐了吗?", "缺参数却拿默认值硬跑", 0.95),
    D03_TOOL_RECALL("D03", "工具召回", "几十个工具里哪几个进候选?", "正确的工具根本没进候选集", 0.95),
    D04_TOOL_SELECT("D04", "工具选择", "候选中选哪一个?", "选了个名字像、语义不同的工具", 1.00),
    D05_ARG_BIND("D05", "参数来源绑定", "这个值来自原话、记忆还是默认值?", "默认值悄悄生效,没人发现", 1.35),
    D06_ARG_COERCE("D06", "参数纠偏", "「一百二」是一百二十还是一万二?", "数量级错、单位错", 1.15),
    D07_CLARIFY("D07", "是否澄清", "直接追问,还是先猜一个?", "该问的没问,不该问的问了", 1.05),
    D08_RETRIEVE_SCOPE("D08", "检索范围", "查最近几条,还是全量?", "召回不足或上下文超长", 0.95),
    D09_MEMORY_RECALL("D09", "记忆召回", "要不要翻上一轮对话?", "重复追问用户已经说过的信息", 0.90),
    D10_PLAN_SHAPE("D10", "计划形态", "单步解决,还是拆成多步?", "拆得过碎,延迟成倍", 0.90),
    D11_STEP_DEPENDENCY("D11", "步骤依赖", "这几步能不能并行?", "明明无关却串行等待", 0.85),
    D12_CONCURRENCY("D12", "并发上限", "同时跑几个?", "打爆下游限流", 0.85),
    D13_BUDGET("D13", "预算分配", "每一步分多少超时?", "某一步吃光全局预算", 0.95),
    D14_RETRY("D14", "重试判定", "这个错误值不值得重试?", "对 400 业务错误死磕三次", 1.00),
    D15_FALLBACK("D15", "降级选择", "失败后走缓存、兜底还是报错?", "静默返回空结果", 1.10),
    D16_SELF_CHECK("D16", "结果自检", "这个结果可信吗?", "金额是 0 也照发", 1.05),
    D17_REQUERY("D17", "是否补查", "证据够不够,要不要再查一轮?", "反复补查,烧 token", 1.10),
    D18_IDEMPOTENCY("D18", "幂等键", "写操作怎么防重复?", "重试/重规划导致重复退款", 1.20),
    D19_ANSWER("D19", "回复生成", "说多少、引用哪条证据?", "把大段上下文全塞进 prompt", 0.90),
    D20_STOP("D20", "结束条件", "继续、升级人工,还是放弃?", "死循环跑到步数上限", 0.80);

    private final String code;
    private final String title;
    private final String question;
    private final String symptom;
    /** 风险权重:直接决定外部副作用的决策点(参数绑定、幂等键)权重更高。 */
    private final double weight;

    DecisionPoint(String code, String title, String question, String symptom, double weight) {
        this.code = code;
        this.title = title;
        this.question = question;
        this.symptom = symptom;
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    /** 这个点在决定什么。写进台账,页面直接展示,不需要再维护一份文档。 */
    public String question() {
        return question;
    }

    /** 出问题时的外在表现。排障时从现象反查决策点靠它。 */
    public String symptom() {
        return symptom;
    }

    public static DecisionPoint of(String code) {
        for (DecisionPoint p : values()) {
            if (p.code.equals(code)) {
                return p;
            }
        }
        throw new IllegalArgumentException("未知决策点: " + code);
    }
}
