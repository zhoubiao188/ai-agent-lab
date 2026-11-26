package com.example.tool.agent;

import com.example.tool.exec.ToolCall;
import com.example.tool.exec.ToolResult;
import com.example.tool.fault.CorruptionKind;
import com.example.tool.fault.FaultInjector;
import com.example.tool.spec.ToolRegistry;
import com.example.tool.spec.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性的"假模型"。它干两件真模型会干的事:产出调用图、以及把参数写错。
 *
 * <p>写错参数这件事是刻意的。真实 trace 里参数错误的分布很集中(见 {@link CorruptionKind}),
 * 所以这里按同样的分布注入,才能测出修复逻辑到底值多少钱。
 * 如果你想接真模型,实现 {@link Planner} 换掉这个类即可。</p>
 */
@Component
public class MockPlanner implements Planner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern ORDER_ID = Pattern.compile("A\\d{5}");

    private final FaultInjector faults;
    private final ToolRegistry registry;

    public MockPlanner(FaultInjector faults, ToolRegistry registry) {
        this.faults = faults;
        this.registry = registry;
    }

    @Override
    public List<ToolCall> plan(String task, String taskKey) {
        String orderId = extractOrderId(task);
        String sku = "SKU-" + (1000 + Math.abs(orderId.hashCode()) % 900);
        // 调用 ID 只允许字母数字:参数里的 $callId.field 引用要靠它做正则匹配
        String p = taskKey.replaceAll("[^A-Za-z0-9]", "") + "c";

        List<ToolCall> calls = new ArrayList<>();
        calls.add(new ToolCall(p + "1", "order.query", obj("orderId", orderId), List.of()));
        calls.add(new ToolCall(p + "2", "logistics.track",
                obj("orderId", orderId, "carrier", "SF"), List.of()));
        calls.add(new ToolCall(p + "3", "inventory.check",
                obj("sku", sku, "quantity", 2), List.of()));
        // 报价单只依赖订单,不依赖物流 —— 分层调度会在这里白白等 200ms
        calls.add(new ToolCall(p + "4", "refund.quote",
                obj("orderId", "$" + p + "1.orderId",
                        "paidAmount", "$" + p + "1.paidAmount",
                        "refundedAmount", "$" + p + "1.refundedAmount"),
                List.of(p + "1")));
        calls.add(new ToolCall(p + "5", "refund.submit",
                obj("quoteId", "$" + p + "4.quoteId",
                        "amount", "$" + p + "4.amount",
                        "reason", "质量问题"),
                List.of(p + "4")));
        calls.add(new ToolCall(p + "6", "ticket.create",
                obj("orderId", orderId, "category", "logistics", "urgent", true), List.of()));

        List<ToolCall> corrupted = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            CorruptionKind kind = faults.corruptionFor(call.id());
            ObjectNode args = kind == null ? call.args() : corrupt(call.args(), kind);
            corrupted.add(kind == null ? call : call.withArgs(args == null ? call.args() : args));
        }
        return corrupted;
    }

    @Override
    public ToolCall rewrite(ToolCall call, List<Violation> violations, String task) {
        ObjectNode args = call.args().deepCopy();
        for (Violation v : violations) {
            if (v.code() != Violation.Code.MISSING_REQUIRED) {
                continue;
            }
            String field = v.path().substring(v.path().lastIndexOf('/') + 1);
            String guess = guess(field, task);
            if (guess != null) {
                args.put(field, guess);
            }
        }
        return call.withArgs(args);
    }

    @Override
    public String summarize(String task, List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        ToolResult order = find(results, "order.query");
        ToolResult quote = find(results, "refund.quote");
        ToolResult refund = find(results, "refund.submit");
        ToolResult logistics = find(results, "logistics.track");
        ToolResult ticket = find(results, "ticket.create");

        if (order != null && order.ok() && order.payload() != null) {
            sb.append("订单 ").append(order.payload().path("orderId").asText())
                    .append(" 当前状态 ").append(order.payload().path("status").asText())
                    .append(",实付 ¥").append(order.payload().path("paidAmount").asDouble()).append("。");
        }
        if (logistics != null && logistics.ok() && logistics.payload() != null) {
            sb.append("物流最新节点:").append(logistics.payload().path("latest").asText())
                    .append("(延误 ").append(logistics.payload().path("delayHours").asInt()).append(" 小时)。");
        }
        if (quote != null && quote.ok() && quote.payload() != null) {
            sb.append("可退金额 ¥").append(quote.payload().path("amount").asDouble()).append("。");
        }
        if (refund != null && refund.ok() && refund.payload() != null) {
            sb.append("已提交退款,退款单号 ").append(refund.payload().path("refundId").asText())
                    .append(",预计 ").append(refund.payload().path("eta").asText()).append("到账。");
        }
        if (ticket != null && ticket.ok() && ticket.payload() != null) {
            sb.append("已为物流异常创建工单 ").append(ticket.payload().path("ticketId").asText())
                    .append("。");
        }
        long degraded = results.stream().filter(ToolResult::fallback).count();
        if (degraded > 0) {
            sb.append("其中 ").append(degraded).append(" 项数据来自降级结果,建议人工复核。");
        }
        List<String> failures = new ArrayList<>();
        for (ToolResult r : results) {
            if (!r.ok()) {
                failures.add(r.tool() + "(" + r.errorKind() + ")");
            }
        }
        if (!failures.isEmpty()) {
            sb.append("未能完成:").append(String.join("、", failures)).append("。");
        }
        return sb.length() == 0 ? "没有拿到可用的工具结果,建议转人工。" : sb.toString();
    }

    private ToolResult find(List<ToolResult> results, String tool) {
        return results.stream().filter(r -> r.tool().equals(tool)).findFirst().orElse(null);
    }

    private ObjectNode corrupt(ObjectNode source, CorruptionKind kind) {
        ObjectNode args = source.deepCopy();
        switch (kind) {
            case SNAKE_KEY -> {
                String key = args.fieldNames().next();
                String snake = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
                if (snake.equals(key)) {
                    return null;
                }
                JsonNode value = args.remove(key);
                args.set(snake, value);
            }
            case STRING_NUMBER -> {
                Iterator<Map.Entry<String, JsonNode>> it = args.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue().isNumber()) {
                        args.put(e.getKey(), e.getValue().asText());
                        return args;
                    }
                }
                return null;
            }
            case ENUM_CASE -> {
                Iterator<Map.Entry<String, JsonNode>> it = args.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    String text = e.getValue().isTextual() ? e.getValue().asText() : "";
                    if (text.isEmpty() || text.startsWith("$") || ORDER_ID.matcher(text).matches()) {
                        continue;
                    }
                    args.put(e.getKey(), text.toUpperCase(Locale.ROOT));
                    return args;
                }
                return null;
            }
            case MISSING_REQUIRED -> {
                // 只删能从任务上下文推断出来的字段,否则"修复"就变成了变魔术
                if (!args.has("orderId")) {
                    return null;
                }
                args.remove("orderId");
            }
            case EXTRA_FIELD -> args.put("timestamp", 1764_000_000_000L);
            case STRING_BOOLEAN -> {
                Iterator<Map.Entry<String, JsonNode>> it = args.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue().isBoolean()) {
                        args.put(e.getKey(), String.valueOf(e.getValue().asBoolean()));
                        return args;
                    }
                }
                return null;
            }
        }
        return args;
    }

    private String guess(String field, String task) {
        return switch (field) {
            case "orderId" -> extractOrderId(task);
            default -> null;
        };
    }

    private String extractOrderId(String task) {
        Matcher m = ORDER_ID.matcher(task == null ? "" : task);
        return m.find() ? m.group() : "A10001";
    }

    private ObjectNode obj(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        ObjectNode node = JSON.objectNode();
        map.forEach((k, v) -> {
            if (v instanceof Integer i) {
                node.put(k, i);
            } else if (v instanceof Long l) {
                node.put(k, l);
            } else if (v instanceof Double d) {
                node.put(k, d);
            } else if (v instanceof Boolean b) {
                node.put(k, b);
            } else {
                node.put(k, String.valueOf(v));
            }
        });
        return node;
    }

    /** 供上层做"工具清单"展示用 */
    public List<String> toolNames() {
        return registry.all().stream().map(s -> s.name()).toList();
    }
}
