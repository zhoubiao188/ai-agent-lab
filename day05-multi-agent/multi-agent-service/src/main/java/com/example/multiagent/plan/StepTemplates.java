package com.example.multiagent.plan;

import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * 槽位 → 步骤的模板。
 *
 * <p>这个类被两处复用,复用的本身就是结论的一部分:</p>
 * <p>1、<b>规划器</b>用它生成"正确的计划"。</p>
 * <p>2、<b>覆盖核对的补丁器</b>用它机械地补上漏掉的步骤 —— 因为槽位和步骤是一一对应的,
 * 所以"拆漏"是三类缺陷里唯一能被机械修好的:不需要模型再想一遍。</p>
 */
public final class StepTemplates {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StepTemplates() {
    }

    /** 在已有计划里找某个工具产出的步骤 id */
    public static Optional<String> findStepByTool(Plan plan, String tool) {
        return plan.steps().stream().filter(s -> s.tool().equals(tool)).map(Step::id).findFirst();
    }

    public static Step build(Plan sofar, TaskSpec task, Slot slot, String newId) {
        return switch (slot) {
            case ORDER -> new Step(newId, "查订单 " + task.orderId(), "order.query",
                    obj("orderId", task.orderId()), java.util.List.of(), java.util.List.of("order"),
                    java.util.List.of("orderId", "sku", "paidAmount", "refundedAmount", "status"),
                    java.util.List.of());
            case LOGISTICS -> new Step(newId, "查物流 " + task.orderId(), "logistics.track",
                    obj("orderId", task.orderId(), "carrier", "SF"), java.util.List.of(), java.util.List.of("logistics"),
                    java.util.List.of("latest", "delayHours", "carrier"), java.util.List.of());
            case INVENTORY -> new Step(newId, "查库存 " + task.sku(), "inventory.check",
                    objInt(task.sku(), task.quantity()), java.util.List.of(), java.util.List.of("inventory"),
                    java.util.List.of("available", "enough"), java.util.List.of());
            case QUOTE -> {
                String orderStep = findStepByTool(sofar, "order.query").orElse(null);
                ObjectNode args = JSON.objectNode();
                args.put("orderId", task.orderId());
                if (orderStep != null) {
                    args.put("paidAmount", "$" + orderStep + ".paidAmount");
                    args.put("refundedAmount", "$" + orderStep + ".refundedAmount");
                }
                yield new Step(newId, "算可退金额", "refund.quote", args,
                        orderStep == null ? java.util.List.of() : java.util.List.of(orderStep),
                        java.util.List.of("quote"), java.util.List.of("quoteId", "amount"),
                        orderStep == null ? java.util.List.of() : java.util.List.of("paidAmount", "refundedAmount"));
            }
            case REFUND -> {
                String quoteStep = findStepByTool(sofar, "refund.quote").orElse(null);
                ObjectNode args = JSON.objectNode();
                args.put("reason", task.reason());
                if (quoteStep != null) {
                    args.put("quoteId", "$" + quoteStep + ".quoteId");
                    args.put("amount", "$" + quoteStep + ".amount");
                }
                yield new Step(newId, "提交退款", "refund.submit", args,
                        quoteStep == null ? java.util.List.of() : java.util.List.of(quoteStep),
                        java.util.List.of("refund"), java.util.List.of("refundId", "status"),
                        quoteStep == null ? java.util.List.of() : java.util.List.of("quoteId", "amount"));
            }
            case TICKET -> {
                String orderStep = findStepByTool(sofar, "order.query").orElse(null);
                ObjectNode args = obj("orderId", task.orderId(), "category", "refund");
                args.put("urgent", false);
                yield new Step(newId, "建人工工单", "ticket.create", args,
                        orderStep == null ? java.util.List.of() : java.util.List.of(orderStep),
                        java.util.List.of("ticket"), java.util.List.of("ticketId", "queue"),
                        java.util.List.of());
            }
        };
    }

    private static ObjectNode obj(String k, String v) {
        ObjectNode n = JSON.objectNode();
        n.put(k, v);
        return n;
    }

    private static ObjectNode obj(String k1, String v1, String k2, String v2) {
        ObjectNode n = obj(k1, v1);
        n.put(k2, v2);
        return n;
    }

    private static ObjectNode objInt(String sku, int quantity) {
        ObjectNode n = JSON.objectNode();
        n.put("sku", sku);
        n.put("quantity", quantity);
        return n;
    }
}
