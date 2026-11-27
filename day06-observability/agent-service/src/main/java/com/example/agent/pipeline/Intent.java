package com.example.agent.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图枚举。关键词带权重,不是等权命中。
 *
 * <p>等权命中会把"订单 ORD-100235 要退款"判成意图不明:订单(查询)和退款各命中一次,
 * 平局。但人一眼就知道这是退款 —— "退款"是动作,"订单"只是宾语。
 * 权重就是把这层常识编码进去,让 D01 的置信度不至于天天都是 0.5。</p>
 */
public enum Intent {

    REFUND("退款", signals("退款", 3, "退货", 3, "退钱", 3, "售后", 2, "不想要", 2),
            List.of("order.get", "price.calc", "policy.lookup", "refund.preview", "refund.commit")),

    QUERY_ORDER("订单查询", signals("物流", 2, "到哪", 2, "订单", 1, "状态", 1),
            List.of("order.get", "order.items", "log.query")),

    STOCK("库存查询", signals("库存", 3, "有货", 3, "还有", 2, "补货", 2),
            List.of("inventory.get", "inventory.reserve")),

    TICKET("工单", signals("工单", 3, "投诉", 3, "催", 2, "反馈", 2),
            List.of("order.get", "ticket.create", "ticket.get")),

    DOC("政策咨询", signals("政策", 3, "规则", 3, "怎么", 2, "流程", 2, "多久", 2),
            List.of("doc.search", "policy.lookup")),

    AFTERSALE("售后综合", signals("换货", 3, "售后", 2, "保修", 3),
            List.of("order.get", "doc.search", "ticket.create"));

    private final String label;
    private final Map<String, Integer> signals;
    private final List<String> preferredTools;

    Intent(String label, Map<String, Integer> signals, List<String> preferredTools) {
        this.label = label;
        this.signals = signals;
        this.preferredTools = preferredTools;
    }

    public String label() {
        return label;
    }

    public Map<String, Integer> signals() {
        return signals;
    }

    /** 该意图下"业务上必然要用到"的工具。召回漏了它们,D03 就该被标红。 */
    public List<String> preferredTools() {
        return preferredTools;
    }

    private static Map<String, Integer> signals(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}
