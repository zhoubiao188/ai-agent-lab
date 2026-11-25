package com.example.rag.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中英混合分词器。
 * <p>不引第三方分词器的原因很实际:BM25 的分词与向量模型的分词必须解耦。
 * 中文按二元组切分(bigram),英文按词切分并做驼峰/下划线拆解,
 * 足够支撑技术文档场景,而且行为完全可预测 —— 出问题时能一眼看出为什么没召回。</p>
 */
public final class Tokenizer {

    /** 英文停用词。只去掉高频虚词,技术词一个都不删。 */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "of", "to", "and", "or", "in", "on",
            "for", "with", "how", "what", "why", "when", "which", "it", "this", "that", "be",
            "do", "does", "can", "i", "you", "my", "we", "as", "at", "by", "from", "not", "if"
    );

    /** 中文停用字。只用于判断二元组是否整组无意义。 */
    private static final Set<String> CJK_STOP = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "就", "都", "也", "被", "把", "对", "从",
            "到", "为", "以", "及", "等", "上", "下", "中", "这", "那", "有", "不", "很", "还",
            "再", "又", "吗", "呢", "啊", "个", "些", "之", "其", "而", "则", "并", "要", "会"
    );

    private Tokenizer() {
    }

    /** 返回按出现顺序排列的 token 列表(可重复),BM25 需要词频所以不去重。 */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cjk = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk.append(c);
                i++;
                continue;
            }
            if (cjk.length() > 0) {
                flushCjk(cjk.toString(), out);
                cjk.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < n) {
                    char d = text.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '-' || d == '#' || d == '@') {
                        i++;
                    } else {
                        break;
                    }
                }
                addLatin(text.substring(start, i), out);
            } else {
                i++;
            }
        }
        if (cjk.length() > 0) {
            flushCjk(cjk.toString(), out);
        }
        return out;
    }

    /** 去重后的 token 集合,用于重排特征计算。 */
    public static List<String> distinct(String text) {
        return new ArrayList<>(new LinkedHashSet<>(tokenize(text)));
    }

    private static void addLatin(String raw, List<String> out) {
        String token = strip(raw).toLowerCase();
        if (token.isEmpty()) {
            return;
        }
        // 带分隔符的复合词:spring.data.redis 同时产出整体与各部分,
        // 这样用户写 "redis 配置" 也能命中
        int split = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '_' || c == '-' || c == '#' || c == '@') {
                split++;
            }
        }
        if (!STOP.contains(token)) {
            out.add(token);
        }
        if (split > 0) {
            for (String part : token.split("[_\\-#@]+")) {
                if (!part.isEmpty() && !STOP.contains(part)) {
                    out.add(part);
                }
            }
            String joined = token.replaceAll("[_\\-#@]+", "");
            if (joined.length() > 1) {
                out.add(joined);
            }
        }
        // 驼峰拆解:SpringBoot -> spring + boot
        String camel = camelSplit(raw);
        if (camel != null && !camel.equals(token)) {
            for (String part : camel.split("\\|")) {
                if (part.length() >= 3 && !STOP.contains(part)) {
                    out.add(part);
                }
            }
        }
    }

    private static String camelSplit(String raw) {
        StringBuilder sb = new StringBuilder();
        int upperRun = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = raw.charAt(i - 1);
                boolean boundary = Character.isLowerCase(prev)
                        || (upperRun == 0 && i + 1 < raw.length() && Character.isLowerCase(raw.charAt(i + 1)));
                if (boundary && sb.length() > 0 && sb.charAt(sb.length() - 1) != '|') {
                    sb.append('|');
                }
                upperRun++;
            } else if (Character.isLowerCase(c)) {
                upperRun = 0;
            }
            sb.append(Character.toLowerCase(c));
        }
        String s = sb.toString();
        return s.indexOf('|') >= 0 ? s : null;
    }

    private static void flushCjk(String run, List<String> out) {
        if (run.length() == 1) {
            if (!CJK_STOP.contains(run)) {
                out.add(run);
            }
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            String bi = run.substring(i, i + 2);
            if (CJK_STOP.contains(String.valueOf(bi.charAt(0))) && CJK_STOP.contains(String.valueOf(bi.charAt(1)))) {
                continue;
            }
            out.add(bi);
        }
    }

    private static String strip(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && !Character.isLetterOrDigit(s.charAt(b))) {
            b++;
        }
        while (e > b && !Character.isLetterOrDigit(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(b, e);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
