package com.example.tool.tools;

import com.example.tool.config.ToolProperties;
import com.example.tool.exec.ExecutionContext;
import com.example.tool.exec.ToolException;
import com.example.tool.spec.JsonSchema;
import com.example.tool.spec.ToolRegistry;
import com.example.tool.spec.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电商售后域的工具集。选这个域不是因为好写,而是因为它天然带齐了工具调用的所有麻烦:
 * 读多写少、有依赖、有幂等要求、有一个慢接口、还有一个"绝不能重试"的写接口。
 *
 * <p>工具实现里刻意<b>不做</b>任何重试和容错 —— 那是 {@code ToolInvoker} 的职责。
 * 混在一起写,就会出现"工具自己重试两次、外层再重试三次"的 6 次放大,
 * 而且日志里根本看不出来。</p>
 */
@Component
public class EcommerceTools {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ToolRegistry registry;
    private final ToolProperties props;
    private final LatencyGate latency;
    /** 幂等表:同一个 idempotencyKey 只真正退一次款 */
    private final Map<String, ObjectNode> refunds = new ConcurrentHashMap<>();

    public EcommerceTools(ToolRegistry registry, ToolProperties props, LatencyGate latency) {
        this.registry = registry;
        this.props = props;
        this.latency = latency;
    }

    @PostConstruct
    public void register() {
        registry.register(ToolSpec.read("order.query", "按订单号查订单详情(金额、状态、SKU)",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "orderId", JsonSchema.string("订单号,形如 A10001").withPattern("A\\d{5}")
                        )), "orderId")),
                this::queryOrder);

        registry.register(ToolSpec.read("logistics.track", "查物流轨迹与异常状态",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "orderId", JsonSchema.string("订单号"),
                                "carrier", JsonSchema.enumOf("承运商", "SF", "JD", "YTO")
                        )), "orderId")),
                this::trackLogistics);

        registry.register(ToolSpec.read("inventory.check", "查 SKU 可售库存",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "sku", JsonSchema.string("商品编码"),
                                "quantity", JsonSchema.integer("需要的数量", 1, 999)
                        )), "sku", "quantity")),
                this::checkInventory);

        registry.register(ToolSpec.read("refund.quote", "根据订单金额与已退金额计算可退金额",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "orderId", JsonSchema.string("订单号"),
                                "paidAmount", JsonSchema.number("实付金额", 0.01, 100000),
                                "refundedAmount", JsonSchema.number("已退金额", 0, 100000)
                        )), "orderId", "paidAmount")),
                this::quoteRefund);

        registry.register(ToolSpec.write("refund.submit", "提交退款(幂等,需要幂等键)",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "quoteId", JsonSchema.string("报价单号"),
                                "amount", JsonSchema.number("退款金额", 0.01, 100000),
                                "reason", JsonSchema.enumOf("退款原因", "七天无理由", "质量问题", "物流破损")
                        )), "quoteId", "amount", "reason"), true),
                this::submitRefund);

        registry.register(ToolSpec.write("ticket.create", "创建人工工单",
                        JsonSchema.object(new LinkedHashMap<>(Map.of(
                                "orderId", JsonSchema.string("订单号"),
                                "category", JsonSchema.enumOf("工单类型", "logistics", "refund", "quality"),
                                "urgent", JsonSchema.bool("是否加急")
                        )), "orderId", "category"), props.ticketIdempotent()),
                this::createTicket);
    }

    private JsonNode queryOrder(JsonNode args, ExecutionContext ctx) {
        sleep(120);
        String orderId = args.get("orderId").asText();
        int seed = Math.abs(orderId.hashCode());
        ObjectNode node = JSON.objectNode();
        node.put("orderId", orderId);
        node.put("sku", "SKU-" + (1000 + seed % 900));
        node.put("paidAmount", 99 + seed % 400);
        node.put("refundedAmount", seed % 7 == 0 ? 30 : 0);
        node.put("status", "PAID");
        node.put("buyer", "用户" + (seed % 9000 + 1000));
        node.put("createdAt", "2026-11-20T10:12:00+08:00");
        return node;
    }

    private JsonNode trackLogistics(JsonNode args, ExecutionContext ctx) {
        // 故意做成最慢的一个:真实链路里物流查询几乎总是调第三方, 200ms 算快的
        sleep(200);
        String orderId = args.get("orderId").asText();
        int seed = Math.abs(orderId.hashCode() / 7);
        ObjectNode node = JSON.objectNode();
        node.put("orderId", orderId);
        node.put("carrier", args.hasNonNull("carrier") ? args.get("carrier").asText() : "SF");
        node.put("latest", seed % 3 == 0 ? "派送异常:收件人电话不通" : "运输中");
        node.put("delayHours", seed % 3 == 0 ? 18 : 2);
        node.put("traces", 4 + seed % 5);
        return node;
    }

    private JsonNode checkInventory(JsonNode args, ExecutionContext ctx) {
        sleep(90);
        String sku = args.get("sku").asText();
        int want = args.get("quantity").asInt();
        int available = Math.abs(sku.hashCode()) % 40;
        ObjectNode node = JSON.objectNode();
        node.put("sku", sku);
        node.put("available", available);
        node.put("enough", available >= want);
        node.put("warehouse", "WH-" + (available % 5 + 1));
        return node;
    }

    private JsonNode quoteRefund(JsonNode args, ExecutionContext ctx) {
        sleep(60);
        double paid = args.get("paidAmount").asDouble();
        double refunded = args.hasNonNull("refundedAmount") ? args.get("refundedAmount").asDouble() : 0;
        // 业务规则不在 schema 里:schema 只能保证"是个 0.01~100000 的数字",
        // "不能超过实付"必须自己判断。参数校验永远只是第一道门。
        if (refunded >= paid) {
            throw ToolException.business("该订单已无可退金额(实付 " + paid + ",已退 " + refunded + ")");
        }
        double amount = Math.round((paid - refunded) * 100) / 100.0;
        ObjectNode node = JSON.objectNode();
        node.put("quoteId", "Q" + Math.abs(args.get("orderId").asText().hashCode() % 100000));
        node.put("orderId", args.get("orderId").asText());
        node.put("amount", amount);
        node.put("expiresInSeconds", 900);
        return node;
    }

    private JsonNode submitRefund(JsonNode args, ExecutionContext ctx) {
        sleep(150);
        String key = ctx.idempotencyKey();
        ObjectNode existing = refunds.get(key);
        if (existing != null) {
            // 幂等命中:重试打进来的第二次请求,返回第一次的结果,不再真正退款
            return existing;
        }
        ObjectNode node = JSON.objectNode();
        node.put("refundId", "R" + Math.abs(key.hashCode() % 1000000));
        node.put("status", "ACCEPTED");
        node.put("amount", args.get("amount").asDouble());
        node.put("eta", "3 个工作日");
        node.put("idempotentReplay", false);
        refunds.put(key, node);
        return node;
    }

    private JsonNode createTicket(JsonNode args, ExecutionContext ctx) {
        sleep(80);
        String orderId = args.get("orderId").asText();
        ObjectNode node = JSON.objectNode();
        node.put("ticketId", "T" + Math.abs((orderId + ctx.idempotencyKey()).hashCode() % 1000000));
        node.put("queue", args.get("category").asText());
        node.put("urgent", args.hasNonNull("urgent") && args.get("urgent").asBoolean());
        node.put("sla", "4h");
        return node;
    }

    public int registeredCount() {
        return registry.size();
    }

    /** 延迟倍率只影响演示速度,不影响任何判定逻辑 */
    private void sleep(long baseMs) {
        long ms = latency.apply(baseMs);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException(com.example.tool.exec.ErrorKind.UNKNOWN, "工具被中断");
        }
    }

    public List<String> names() {
        return List.of("order.query", "logistics.track", "inventory.check",
                "refund.quote", "refund.submit", "ticket.create");
    }
}
