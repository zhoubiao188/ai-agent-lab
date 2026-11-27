package com.example.o11y.redact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Span 属性脱敏。
 *
 * <p>追踪系统最容易变成"第二个数据库泄露源":为了排障,大家把用户原话、工具参数、工具返回
 * 全都塞进 span。半年后有人把 trace 导出给外包分析,手机号和身份证就跟着出去了。</p>
 *
 * <p>这里的策略是"能分组就不留原文":手机号保留前 3 后 4,同时算一个稳定的短哈希,
 * 既能按用户聚合(同一用户 hash 相同),又拿不到明文。哈希必须是稳定的,
 * 不能用随机盐,否则跨 Trace 聚合直接失效。</p>
 */
public final class Redactor {

    /** 脱敏结果:文本 + 命中次数。次数进 Span,用来发现"这个字段一直在泄露"。 */
    public record Result(String text, int hits) {
    }

    private record Rule(String name, Pattern pattern, Mask mask) {
    }

    private interface Mask {
        String apply(String raw);
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        RULES.add(new Rule("phone",
                Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)"),
                raw -> raw.substring(0, 3) + "****" + raw.substring(raw.length() - 4)));
        RULES.add(new Rule("idCard",
                Pattern.compile("(?<!\\d)(\\d{17}[\\dXx])(?!\\d)"),
                raw -> raw.substring(0, 6) + "********" + raw.substring(raw.length() - 4)));
        RULES.add(new Rule("bankCard",
                Pattern.compile("(?<!\\d)(\\d{16,19})(?!\\d)"),
                raw -> "**** **** **** " + raw.substring(raw.length() - 4)));
        RULES.add(new Rule("email",
                Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})"),
                raw -> raw.charAt(0) + "***@" + raw.substring(raw.indexOf('@') + 1)));
        RULES.add(new Rule("apiKey",
                Pattern.compile("\\b(sk|ak|pk)-[A-Za-z0-9]{12,}\\b"),
                raw -> raw.substring(0, 5) + "***redacted***"));
    }

    /**
     * 对一段文本做脱敏。返回的 hits 是该文本命中的规则数,用来在页面上提示"这条 trace 含敏感数据"。
     */
    public Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        String out = text;
        int hits = 0;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean matched = false;
            while (m.find()) {
                matched = true;
                sb.append(out, last, m.start());
                sb.append(rule.mask().apply(m.group()));
                last = m.end();
                hits++;
            }
            if (matched) {
                sb.append(out.substring(last));
                out = sb.toString();
            }
        }
        return new Result(out, hits);
    }

    /** 属性键本身敏感时(密码、token、authorization),值整体替换,不做局部脱敏。 */
    public boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("passwd") || k.contains("secret")
                || k.contains("token") && !k.contains("tokens")
                || k.contains("authorization") || k.contains("cookie") || k.contains("credential");
    }

    /**
     * 稳定短哈希:用于把原始值替换成"同值同 hash"的分组键。
     * 取 SHA-256 的前 8 个 hex 字符,碰撞概率对排障分组足够低。
     */
    public String fingerprint(String raw) {
        if (raw == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
