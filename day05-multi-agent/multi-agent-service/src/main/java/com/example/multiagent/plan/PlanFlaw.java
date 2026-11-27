package com.example.multiagent.plan;

/** 三类计划缺陷。名字对齐真实世界的说法:拆漏、拆错、拆多。 */
public enum PlanFlaw {

    /** 拆漏:用户要 3 件事,计划只做了 2 件 */
    UNDER_COVER("拆漏", "漏掉一个诉求"),

    /** 拆错:依赖断开,写操作的参数指向一个还没跑的步骤(或者干脆自己编) */
    WRONG_ORDER("拆错", "依赖断开/顺序颠倒"),

    /** 拆多:一步能干的事拆成两步,重复调用同一工具 */
    OVER_SPLIT("拆多", "重复步骤");

    private final String label;
    private final String symptom;

    PlanFlaw(String label, String symptom) {
        this.label = label;
        this.symptom = symptom;
    }

    public String label() {
        return label;
    }

    public String symptom() {
        return symptom;
    }
}
