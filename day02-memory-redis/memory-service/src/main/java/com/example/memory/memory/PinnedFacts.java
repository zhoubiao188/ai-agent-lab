package com.example.memory.memory;

import com.example.memory.domain.Msg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键事实提取器 —— 对抗"摘要漂移"的确定性手段。
 *
 * <p>纯 LLM 摘要有个绕不过去的毛病:每压缩一次就可能丢一次细节,压到第 5 次时
 * "订单 A20261123001" 变成 "该订单","899.00 元" 变成 "约 900 元"。这在客服场景是事故,
 * 在财务场景是灾难。</p>
 *
 * <p>所以关键标识符<b>不交给模型记</b>,而是用正则从被压缩的消息里抽出来,单独存一份
 * {@code pinnedFacts},每次请求都原样注入 system。规则提取的召回率不如模型,但它 100% 保真、
 * 零成本、可单测 —— 这正是"用确定性手段兜住概率性组件"的典型做法。</p>
 */
public final class PinnedFacts {

    private static final Pattern ORDER_NO = Pattern.compile(
            "(?:订单|工单|退款单|运单|票号|流水号|编号|合同|账号)[号码]?\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9\\-_]{5,})");
    private static final Pattern CODE = Pattern.compile("\\b[A-Z]{2,8}[-_]?\\d{5,}\\b");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:[¥￥$]\\s?\\d+(?:\\.\\d+)?)|(?:\\d+(?:\\.\\d+)?\\s?(?:元|块钱|块|万元|美元))");
    private static final Pattern DATE = Pattern.compile(
            "\\d{4}\\s?[-/.年]\\s?\\d{1,2}\\s?[-/.月]\\s?\\d{1,2}\\s?日?|\\d{1,2}月\\d{1,2}[日号]");
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern PERCENT = Pattern.compile("\\d+(?:\\.\\d+)?\\s?%");
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}[:：]\\d{2}\\b");

    private static final int MAX_FACTS_PER_TURN = 8;

    private PinnedFacts() {
    }

    public static List<String> extract(Collection<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            sb.append(m.readable()).append('\n');
        }
        return extract(sb.toString());
    }

    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        collect(facts, ORDER_NO, text, "单号");
        collect(facts, CODE, text, "编号");
        collect(facts, AMOUNT, text, "金额");
        collect(facts, DATE, text, "日期");
        collect(facts, PHONE, text, "手机号");
        collect(facts, PERCENT, text, "比例");
        collect(facts, TIME, text, "时间");
        List<String> list = new ArrayList<>(facts);
        return list.size() > MAX_FACTS_PER_TURN ? list.subList(0, MAX_FACTS_PER_TURN) : list;
    }

    private static void collect(LinkedHashSet<String> out, Pattern pattern, String text, String label) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String value = (m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group()).trim();
            value = value.replaceAll("\\s+", " ");
            if (value.length() < 2) {
                continue;
            }
            String fact = label + " " + value;
            if (out.size() >= MAX_FACTS_PER_TURN) {
                return;
            }
            out.add(fact);
        }
    }
}
