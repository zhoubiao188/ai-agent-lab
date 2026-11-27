package com.example.multiagent.plan;

/** 意图槽位。用户说"帮我看看这单还能退多少,能退就退了",拆开就是 quote + refund 两个槽位。 */
public enum Slot {

    ORDER("order", "查订单"),
    LOGISTICS("logistics", "查物流"),
    INVENTORY("inventory", "查库存"),
    QUOTE("quote", "算可退金额"),
    REFUND("refund", "提交退款"),
    TICKET("ticket", "建工单");

    private final String key;
    private final String label;

    Slot(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static Slot of(String key) {
        for (Slot s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知槽位: " + key);
    }
}
