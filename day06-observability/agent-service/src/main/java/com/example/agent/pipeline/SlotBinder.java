package com.example.agent.pipeline;

import com.example.agent.fault.FaultPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位绑定 + 参数纠偏。
 *
 * <p>绑定顺序固定为"原话 > 记忆 > 默认值",并且每一步都记来源。这个顺序本身没有魔法,
 * 有价值的是"记来源"这一步:线上 90% 的"金额怎么是 0"都能在这一行上找到答案。</p>
 */
@Component
public class SlotBinder {

    private static final Pattern ORDER_ID = Pattern.compile("ORD-\\d{6}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKU = Pattern.compile("SKU-\\d{3}", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_WITH_UNIT = Pattern.compile(
            "([0-9]+(?:\\.[0-9]+)?|[零一二三四五六七八九十百千万两]+)\\s*(?:元|块钱|块)");
    private static final Pattern AMOUNT_AFTER_REFUND = Pattern.compile(
            "退\\s*([0-9]+(?:\\.[0-9]+)?|[零一二三四五六七八九十百千万两]+)");
    private static final Map<String, String> REASONS = Map.of(
            "不想要", "七天无理由",
            "不喜欢", "七天无理由",
            "质量", "质量问题",
            "坏", "质量问题",
            "破损", "运输破损",
            "发错", "发错货");

    /** 会话记忆:只记最近一次订单号,够演示"记忆召回"这条链路了。 */
    private final Map<String, String> lastOrderByUser = new ConcurrentHashMap<>();

    public record BindResult(Slots slots, List<String> required, List<String> defaults, double confidence) {
    }

    /** 每个意图真正必需的槽位。不区分这一点,问一句政策也会被算成"缺 3 个槽位"。 */
    private static List<String> requiredSlots(Intent intent) {
        return switch (intent) {
            case REFUND -> List.of("orderId", "amountCents");
            case QUERY_ORDER, TICKET, AFTERSALE -> List.of("orderId");
            case STOCK -> List.of("sku");
            case DOC -> List.of();
        };
    }

    public BindResult bind(String question, String userId, FaultPlan plan, Intent intent) {
        Slots slots = new Slots();
        String q = question == null ? "" : question;

        Matcher orderMatcher = ORDER_ID.matcher(q);
        if (orderMatcher.find()) {
            slots.put("orderId", orderMatcher.group().toUpperCase(), Slots.TEXT);
            lastOrderByUser.put(userId, orderMatcher.group().toUpperCase());
        } else {
            String remembered = lastOrderByUser.get(userId);
            if (remembered != null && (q.contains("这个订单") || q.contains("刚才") || q.contains("上次"))) {
                slots.put("orderId", remembered, Slots.MEMORY);
            } else {
                slots.put("orderId", "ORD-100234", Slots.DEFAULT);
            }
        }

        Matcher skuMatcher = SKU.matcher(q);
        if (skuMatcher.find()) {
            slots.put("sku", skuMatcher.group().toUpperCase(), Slots.TEXT);
        }

        Integer amountCents = extractAmountCents(q);
        boolean amountDropped = plan.dropAmountSlot();
        if (amountCents != null && !amountDropped) {
            slots.put("amountCents", amountCents, Slots.TEXT);
        } else {
            // 剧本 ARG_DRIFT:金额缺失时用默认值兜底。这就是线上最危险的那一行代码
            slots.put("amountCents", 0, Slots.DEFAULT);
        }

        String reason = "七天无理由";
        for (Map.Entry<String, String> e : REASONS.entrySet()) {
            if (q.contains(e.getKey())) {
                reason = e.getValue();
                break;
            }
        }
        slots.put("reason", reason, q.contains("不想要") || q.contains("质量") || q.contains("破损")
                ? Slots.TEXT : Slots.DEFAULT);

        List<String> required = requiredSlots(intent);
        List<String> defaults = required.stream()
                .filter(slot -> Slots.DEFAULT.equals(slots.source(slot)))
                .toList();
        double confidence = required.isEmpty() ? 0.96
                : defaults.isEmpty() ? 0.94
                : Math.max(0.15, 0.94 - 0.45 * defaults.size());
        return new BindResult(slots, required, defaults, Math.round(confidence * 100) / 100.0);
    }

    /**
     * 金额解析:先找带单位的,再找"退 X"结构,最后尝试中文数字。
     * 单位缺失时按元处理,但置信度会降 —— 这是 D06 要记录的东西,不是悄悄猜。
     */
    Integer extractAmountCents(String q) {
        Matcher withUnit = AMOUNT_WITH_UNIT.matcher(q);
        if (withUnit.find()) {
            return toCents(withUnit.group(1));
        }
        Matcher afterRefund = AMOUNT_AFTER_REFUND.matcher(q);
        if (afterRefund.find()) {
            return toCents(afterRefund.group(1));
        }
        return null;
    }

    private Integer toCents(String raw) {
        try {
            double value = raw.matches("[0-9]+(?:\\.[0-9]+)?")
                    ? Double.parseDouble(raw)
                    : CnNumber.parse(raw);
            if (value <= 0) {
                return null;
            }
            return (int) Math.round(value * 100);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 中文数字解析,只覆盖售后场景里真会出现的量级(几块到几万)。
     *
     * <p>「一百二」在口语里是 120 不是 102,所以尾数是裸数字时按最后一个单位的 1/10 计。
     * 这类细节不做,用户说"退一百二"就会变成 102 元,金额算错还查不出来。</p>
     */
    static final class CnNumber {

        private static final Map<Character, Integer> DIGITS = new java.util.HashMap<>();
        private static final Map<Character, Integer> UNITS = new java.util.HashMap<>();

        static {
            String digits = "零一二三四五六七八九";
            for (int i = 0; i < digits.length(); i++) {
                DIGITS.put(digits.charAt(i), i);
            }
            DIGITS.put('两', 2);
            UNITS.put('十', 10);
            UNITS.put('百', 100);
            UNITS.put('千', 1000);
            UNITS.put('万', 10000);
        }

        static int parse(String text) {
            int section = 0;
            int number = 0;
            int lastUnit = 0;
            boolean trailingDigit = false;
            List<Integer> sections = new ArrayList<>();

            for (char c : text.toCharArray()) {
                if (DIGITS.containsKey(c)) {
                    number = DIGITS.get(c);
                    trailingDigit = true;
                } else if (UNITS.containsKey(c)) {
                    int unit = UNITS.get(c);
                    if (unit == 10000) {
                        section = (section + Math.max(number, 1)) * unit;
                        sections.add(section);
                        section = 0;
                    } else {
                        section += Math.max(number, 1) * unit;
                    }
                    number = 0;
                    lastUnit = unit;
                    trailingDigit = false;
                }
            }
            int total = sections.stream().mapToInt(Integer::intValue).sum() + section;
            if (trailingDigit) {
                total += lastUnit >= 100 ? number * (lastUnit / 10) : number;
            }
            return total;
        }

        private CnNumber() {
        }
    }
}
