package com.example.agent.tool;

import com.example.agent.fault.FaultPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 12 个电商售后工具。数据全部由 orderId 哈希生成,没有外部依赖,clone 下来就能跑。
 *
 * <p>三个刻意保留的"脏东西":</p>
 * <p>1、{@code refund.commit} 返回 HTTP 200 + {@code accepted},但账务侧的回显金额可能对不上。
 * 真实系统里这种"接口成功、业务没落地"是最难查的一类;</p>
 * <p>2、写操作带幂等键,重复提交会返回第一次的结果并标记 duplicate —— 重试与重规划都会踩到;</p>
 * <p>3、所有延迟都走 {@link ToolCallContext#latency(long)},虚拟时钟能把 900ms 的慢调用
 * 变成零成本,评测才能跑 200 次。</p>
 */
@Component
public class DemoTools {

    private final Map<String, ToolResult> idempotencyIndex = new ConcurrentHashMap<>();

    public ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(orderGet());
        registry.register(orderItems());
        registry.register(priceCalc());
        registry.register(policyLookup());
        registry.register(refundPreview());
        registry.register(refundCommit());
        registry.register(inventoryGet());
        registry.register(inventoryReserve());
        registry.register(ticketCreate());
        registry.register(ticketGet());
        registry.register(docSearch());
        registry.register(logQuery());
        return registry;
    }

    public void reset() {
        idempotencyIndex.clear();
    }

    // ---- 读 ----

    private ToolSpec orderGet() {
        return ToolSpec.of("order.get", "按订单号查询订单状态、实付金额、商品与下单时间")
                .keywords("订单", "order", "单号", "物流", "状态")
                .latency(70)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    ctx.latency(70);
                    failIfInjected(ctx);
                    Map<String, Object> data = order(orderId);
                    return ToolResult.of(data, "订单 " + orderId + " 状态 " + data.get("status")
                            + ",实付 " + yuan((Integer) data.get("paidCents")));
                })
                .build();
    }

    private ToolSpec orderItems() {
        return ToolSpec.of("order.items", "查询订单包含的商品明细")
                .keywords("商品", "明细", "买了什么", "件")
                .latency(55)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    ctx.latency(55);
                    failIfInjected(ctx);
                    return ToolResult.of(Map.of(
                            "orderId", orderId,
                            "items", List.of(Map.of("sku", skuOf(orderId), "qty", 1, "title", titleOf(orderId))),
                            "count", 1), "订单含 1 件商品");
                })
                .build();
    }

    private ToolSpec priceCalc() {
        return ToolSpec.of("price.calc", "计算订单当前可退金额(扣除已使用优惠)")
                .keywords("金额", "可退", "多少钱", "价格", "算一下")
                .latency(45)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    ctx.latency(45);
                    failIfInjected(ctx);
                    int paid = paidOf(orderId);
                    int refundable = paid - Math.floorMod(orderId.hashCode(), 5) * 100;
                    return ToolResult.of(Map.of("orderId", orderId, "paidCents", paid,
                            "refundableCents", refundable), "可退 " + yuan(refundable));
                })
                .build();
    }

    private ToolSpec policyLookup() {
        return ToolSpec.of("policy.lookup", "查询售后政策条款(退款期限、例外商品)")
                .keywords("政策", "规则", "多久", "期限", "能不能")
                .latency(35)
                .handler((args, ctx) -> {
                    String topic = str(args, "topic", "退款");
                    ctx.latency(35);
                    failIfInjected(ctx);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("topic", topic);
                    data.put("maxDays", 7);
                    data.put("rule", "签收 7 天内且商品完好可全额退;生鲜、定制类除外");
                    data.put("exception", "已拆封的贴身用品不支持无理由退货");
                    return ToolResult.of(data, "7 天无理由,生鲜与定制除外");
                })
                .build();
    }

    private ToolSpec refundPreview() {
        return ToolSpec.of("refund.preview", "试算退款是否被规则允许,不产生任何写入")
                .keywords("试算", "能不能退", "退款", "校验")
                .latency(40)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    int amount = num(args, "amountCents", 0);
                    ctx.latency(40);
                    failIfInjected(ctx);
                    int paid = paidOf(orderId);
                    boolean allowed = amount > 0 && amount <= paid;
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("allowed", allowed);
                    data.put("amountCents", amount);
                    data.put("paidCents", paid);
                    data.put("reason", allowed ? "符合 7 天无理由" : "金额超出实付或为 0");
                    return ToolResult.of(data, allowed ? "允许退款" : "试算不通过");
                })
                .build();
    }

    // ---- 写 ----

    private ToolSpec refundCommit() {
        return ToolSpec.of("refund.commit", "提交退款单(写操作,需要幂等键)")
                .keywords("退款", "提交", "处理", "退钱")
                .write()
                .latency(95)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    int amount = num(args, "amountCents", 0);
                    String key = str(args, "idempotencyKey", orderId + ":" + amount);
                    ctx.latency(95);
                    failIfInjected(ctx);

                    ToolResult existing = idempotencyIndex.get(key);
                    if (existing != null) {
                        return ToolResult.write(existing.data(),
                                Map.of("orderId", orderId, "amountCents", existing.data().get("amountCents"),
                                        "duplicate", true),
                                "幂等命中,未重复写入");
                    }

                    FaultPlan plan = ctx.run().plan();
                    // 沉默失败:接口 200,账务侧实际入账 0 元
                    int echoAmount = plan.silentWrite() ? 0 : amount;
                    Map<String, Object> echo = new LinkedHashMap<>();
                    echo.put("orderId", orderId);
                    echo.put("amountCents", echoAmount);
                    echo.put("duplicate", false);

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("accepted", true);
                    data.put("refundId", "RF-" + Math.abs(key.hashCode() % 900000 + 100000));
                    data.put("orderId", orderId);
                    data.put("amountCents", amount);
                    data.put("idempotencyKey", key);
                    data.put("ledgerAmountCents", echoAmount);

                    ToolResult result = ToolResult.write(data, echo, "退款单已受理");
                    idempotencyIndex.put(key, result);
                    // 注意:工具只管如实回显,不判断"回显对不对"。
                    // 判断在调用方(以及追踪层),这样埋点才能落在真正做比较的那一层。
                    return result;
                })
                .build();
    }

    private ToolSpec inventoryReserve() {
        return ToolSpec.of("inventory.reserve", "锁定库存(写操作,需要幂等键)")
                .keywords("锁库存", "预留", "占用")
                .write()
                .latency(85)
                .handler((args, ctx) -> {
                    String sku = str(args, "sku", "SKU-201");
                    ctx.latency(85);
                    failIfInjected(ctx);
                    return ToolResult.write(
                            Map.of("sku", sku, "reserved", true, "holdId", "HD-" + Math.abs(sku.hashCode() % 100000)),
                            Map.of("sku", sku, "reserved", true), "库存已锁定");
                })
                .build();
    }

    private ToolSpec ticketCreate() {
        return ToolSpec.of("ticket.create", "创建售后工单(写操作,需要幂等键)")
                .keywords("工单", "投诉", "催", "反馈")
                .write()
                .latency(80)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    String summary = str(args, "summary", "售后咨询");
                    ctx.latency(80);
                    failIfInjected(ctx);
                    String ticketId = "T-" + Math.abs((orderId + summary).hashCode() % 900000 + 100000);
                    return ToolResult.write(
                            Map.of("ticketId", ticketId, "orderId", orderId, "status", "OPEN", "summary", summary),
                            Map.of("ticketId", ticketId, "orderId", orderId), "工单已创建 " + ticketId);
                })
                .build();
    }

    private ToolSpec inventoryGet() {
        return ToolSpec.of("inventory.get", "查询 SKU 可售库存与仓库")
                .keywords("库存", "有货", "还有", "sku")
                .latency(60)
                .handler((args, ctx) -> {
                    String sku = str(args, "sku", "SKU-201");
                    ctx.latency(60);
                    failIfInjected(ctx);
                    int available = 3 + Math.floorMod(sku.hashCode(), 20);
                    return ToolResult.of(Map.of("sku", sku, "available", available,
                            "warehouse", "WH-" + (1 + Math.floorMod(sku.hashCode(), 3))),
                            "库存 " + available + " 件");
                })
                .build();
    }

    private ToolSpec ticketGet() {
        return ToolSpec.of("ticket.get", "查询订单关联的售后工单")
                .keywords("工单", "进度", "处理到哪")
                .latency(50)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    ctx.latency(50);
                    failIfInjected(ctx);
                    return ToolResult.of(Map.of("orderId", orderId, "tickets", List.of(),
                            "count", 0), "暂无工单");
                })
                .build();
    }

    private ToolSpec docSearch() {
        return ToolSpec.of("doc.search", "在售后知识库中检索政策与操作手册片段")
                .keywords("怎么", "政策", "规则", "文档", "说明", "流程")
                .latency(110)
                .handler((args, ctx) -> {
                    String query = str(args, "query", "退款");
                    ctx.latency(110);
                    failIfInjected(ctx);
                    boolean verbose = ctx.run().plan().verboseDocs();
                    int count = verbose ? 40 : 3;
                    List<Map<String, Object>> docs = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        docs.add(Map.of(
                                "id", "DOC-" + (100 + i),
                                "title", query + " 处理规范 第 " + (i + 1) + " 节",
                                "text", verbose
                                        ? "本章节描述" + query + "在跨境、预售、组合优惠、部分签收等 12 种边界场景下的处理口径,"
                                        + "并列出了每种场景需要人工复核的字段与时限要求。第 " + (i + 1) + " 条强调:"
                                        + "金额以账务中心为准,前端展示仅作参考,出现差异时必须回查流水号而非直接重试。"
                                        : "签收 7 天内可退,生鲜除外。"));
                    }
                    return ToolResult.of(Map.of("query", query, "docs", docs, "count", docs.size()),
                            "命中 " + docs.size() + " 个片段");
                })
                .build();
    }

    private ToolSpec logQuery() {
        return ToolSpec.of("log.query", "查询订单相关的操作流水")
                .keywords("流水", "日志", "操作记录", "谁改的")
                .latency(65)
                .handler((args, ctx) -> {
                    String orderId = str(args, "orderId", "ORD-100234");
                    ctx.latency(65);
                    failIfInjected(ctx);
                    return ToolResult.of(Map.of("orderId", orderId, "events", List.of(
                            Map.of("at", "2026-11-20T10:12:03", "op", "CREATE"),
                            Map.of("at", "2026-11-21T09:04:44", "op", "PAY"))), "2 条流水");
                })
                .build();
    }

    // ---- 故障 ----

    private void failIfInjected(ToolCallContext ctx) {
        if (ctx.shouldFail()) {
            throw new ToolException(ctx.injectedError(),
                    "注入故障:第 " + ctx.attempt() + " 次调用返回 " + ctx.injectedError().label());
        }
    }

    // ---- 确定性数据 ----

    /**
     * 固定订单表 + 哈希兜底。
     *
     * <p>示例订单必须固定金额:如果金额是哈希出来的,"退 299 元"这句示例问题就会随机变成
     * "超过实付",演示时好一次坏一次。这类"演示数据不自洽"的坑,自己在写 demo 时一定会遇到一次。</p>
     */
    private static final Map<String, int[]> FIXED_ORDERS = Map.of(
            "ORD-100234", new int[]{29900, 201, 0},
            "ORD-100235", new int[]{12800, 305, 0},
            "ORD-100236", new int[]{56800, 118, 1},
            "ORD-100237", new int[]{39900, 222, 0});

    private Map<String, Object> order(String orderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("userId", "U-" + (80000 + Math.floorMod(orderId.hashCode(), 9999)));
        data.put("status", "SHIPPED".equals(statusOf(orderId)) ? "SHIPPED" : "PAID");
        data.put("paidCents", paidOf(orderId));
        data.put("sku", skuOf(orderId));
        data.put("title", titleOf(orderId));
        data.put("paidAt", "2026-11-20T10:12:03");
        return data;
    }

    private String statusOf(String orderId) {
        int[] fixed = FIXED_ORDERS.get(orderId);
        return fixed != null && fixed[2] == 1 ? "SHIPPED" : "PAID";
    }

    private int paidOf(String orderId) {
        int[] fixed = FIXED_ORDERS.get(orderId);
        return fixed != null ? fixed[0] : 9900 + Math.floorMod(orderId.hashCode(), 60) * 500;
    }

    private String skuOf(String orderId) {
        int[] fixed = FIXED_ORDERS.get(orderId);
        return fixed != null ? "SKU-" + fixed[1] : "SKU-" + (100 + Math.floorMod(orderId.hashCode(), 40));
    }

    private String titleOf(String orderId) {
        String[] titles = {"无线降噪耳机", "机械键盘 87 键", "显示器支架", "便携充电宝", "人体工学椅"};
        return titles[Math.floorMod(orderId.hashCode(), titles.length)];
    }

    private static String str(Map<String, Object> args, String key, String fallback) {
        Object v = args.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private static int num(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String yuan(int cents) {
        return String.format("%.2f 元", cents / 100.0);
    }
}
