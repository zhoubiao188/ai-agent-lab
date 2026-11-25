package com.example.rag.embed;

import org.springframework.beans.factory.annotation.Value;

/**
 * 本地向量化实现:字符级 n-gram 特征哈希(feature hashing)。
 * <p>它做的是把"文本 → 稀疏的 n-gram 计数 → 哈希投影到固定维度"这件事,
 * 效果上等价于一个字符级向量空间模型,能容忍拼写差异、语序变化、词形变化,
 * 因此适合在没有 API Key 的环境里跑通全链路、跑评测基线。</p>
 *
 * <p>它不等价于语义向量:不理解同义词,不区分"苹果"与"苹果手机"。
 * 生产上把 provider 换成 openai 接 bge-m3 / text-embedding-3,
 * 接口不变,分块与融合逻辑一行都不用改。</p>
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    private final int dim;
    private final String name;

    public HashingEmbeddingModel(@Value("${rag.embed.dim:512}") int dim) {
        this.dim = dim;
        this.name = "hashing-char-ngram-v1/" + dim;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        if (text == null || text.isEmpty()) {
            return v;
        }
        String norm = normalize(text);
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= norm.length(); i++) {
            char c = i < norm.length() ? norm.charAt(i) : ' ';
            if (isCjk(c)) {
                flushWord(word, v);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, v);
                word.append(c);
            } else {
                flushCjk(cjk, v);
                flushWord(word, v);
            }
        }
        l2Normalize(v);
        return v;
    }

    private void flushCjk(StringBuilder run, float[] v) {
        if (run.length() == 0) {
            return;
        }
        String s = run.toString();
        for (int i = 0; i < s.length(); i++) {
            if (s.length() > 1) {
                // 单字权重低,只做兜底,让"锁"这种单字查询不至于零命中
                add(v, "#u" + s.charAt(i), 0.35f);
            }
            if (i + 1 < s.length()) {
                add(v, "#b" + s.charAt(i) + "" + s.charAt(i + 1), 1.0f);
            }
        }
        run.setLength(0);
    }

    private void flushWord(StringBuilder word, float[] v) {
        if (word.length() == 0) {
            return;
        }
        String w = word.toString();
        add(v, "w:" + w, 1.2f);
        // 词首词尾加标记,让 "start" 与 "starter" 共享前缀特征但不会被当成同一个词
        String padded = "«" + w + "»";
        for (int i = 0; i + 3 <= padded.length(); i++) {
            add(v, "c:" + padded.substring(i, i + 3), 0.45f);
        }
        word.setLength(0);
    }

    /** 带符号的哈希:同一特征永远落在同一维度同一符号上,不同特征冲突时相互抵消部分噪声。 */
    private void add(float[] v, String feature, float weight) {
        long h = fnv1a64(feature);
        int bucket = (int) Math.floorMod(h, (long) dim);
        float sign = ((h >>> 33) & 1L) == 0L ? 1f : -1f;
        v[bucket] += sign * weight;
    }

    private static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum <= 1e-12) {
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                sb.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
