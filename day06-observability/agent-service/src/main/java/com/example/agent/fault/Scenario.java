package com.example.agent.fault;

/**
 * 演示与评测用的故障剧本。
 *
 * <p>为什么要"剧本"而不是随机故障:随机故障只能证明"系统没崩",证明不了"定位准不准"。
 * 排障能力是可量化的 —— 我知道这次故障埋在哪,就看你的排序把不把它排进前三。
 * 每个剧本都是确定性的,同一个 seed 跑两次结果完全一致,数字才能写进文章。</p>
 */
public enum Scenario {

    NORMAL("正常", "全链路无故障,用作基线", null),
    SLOW_TOOL("慢工具", "订单查询耗时超过单步预算,触发超时与一次重试", "order.get"),
    RETRY_STORM("重试风暴", "库存查询连续两次 503,第三次才成功", "inventory.get"),
    SILENT_FAILURE("沉默失败", "退款写入返回 200,但账务回显金额为 0,业务流程不校验该字段", "refund.commit"),
    COST_BLOWUP("成本失控", "知识库检索返回超大片段,自检不通过后连续补查,答案 prompt 膨胀数倍", "doc.search"),
    ARG_DRIFT("参数漂移", "用户没给退款金额,绑定环节用默认值 0 元兜底", "refund.commit");

    private final String label;
    private final String description;
    private final String culpritTool;

    Scenario(String label, String description, String culpritTool) {
        this.label = label;
        this.description = description;
        this.culpritTool = culpritTool;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 剧本埋点对应的工具,评测时用来标注标准答案。 */
    public String culpritTool() {
        return culpritTool;
    }

    public static Scenario of(String name) {
        if (name == null || name.isBlank()) {
            return NORMAL;
        }
        for (Scenario s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label().equals(name)) {
                return s;
            }
        }
        return NORMAL;
    }
}
