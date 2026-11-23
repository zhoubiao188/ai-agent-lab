package com.example.memory.tool.impl;

import com.example.memory.tool.AgentTool;
import com.example.memory.tool.Schemas;
import com.example.memory.tool.ToolContext;
import com.example.memory.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建售后工单 —— 带<b>副作用</b>的工具。
 * <p>这类工具是 Agent 最容易出事的地方:模型可能重复调用、可能参数缺失。
 * 这里用「会话内幂等键」兜住重复提交,并返回可展示的工单号。</p>
 */
@Component
public class TicketTool implements AgentTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    /** key = conversationId + ':' + 摘要,用于幂等 */
    private final Map<String, String> created = new ConcurrentHashMap<>();

    private final Map<String, String> tickets = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "create_support_ticket";
    }

    @Override
    public String description() {
        return "为用户创建售后工单,交由人工客服跟进。仅在用户明确要求「转人工」「投诉」「报障」"
                + "或知识库无法回答时调用。同一会话内相同诉求只会创建一次。";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = Schemas.object();
        Schemas.string(schema, "summary", "一句话概括用户诉求,不超过 50 字", true);
        Schemas.string(schema, "detail", "补充信息:订单号、问题现象、用户期望的处理结果", false);
        Schemas.string(schema, "priority", "优先级:low / normal / high,默认 normal", false);
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String summary = Schemas.str(args, "summary", "").trim();
        if (summary.isEmpty()) {
            return ToolResult.fail("summary 不能为空", System.currentTimeMillis() - start);
        }
        String detail = Schemas.str(args, "detail", "");
        String priority = Schemas.str(args, "priority", "normal");

        String idempotentKey = ctx.conversationId() + ":" + Integer.toHexString(summary.hashCode());
        String existing = created.get(idempotentKey);
        if (existing != null) {
            return ToolResult.ok("该诉求已创建过工单,工单号 %s(幂等命中,未重复创建)。".formatted(existing),
                    System.currentTimeMillis() - start);
        }

        String ticketNo = "T" + LocalDateTime.now().format(FMT) + SEQ.incrementAndGet();
        tickets.put(ticketNo, summary + " | " + detail);
        created.put(idempotentKey, ticketNo);
        return ToolResult.ok("工单创建成功。工单号:%s,优先级:%s,摘要:%s。请告知用户工单号并说明 24 小时内会有客服联系。"
                .formatted(ticketNo, priority, summary), System.currentTimeMillis() - start);
    }

    public Map<String, String> all() {
        return Map.copyOf(tickets);
    }
}
