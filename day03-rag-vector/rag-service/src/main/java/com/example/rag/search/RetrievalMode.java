package com.example.rag.search;

/** 检索模式。四种模式对应文章里的四组消融实验。 */
public enum RetrievalMode {
    /** 纯关键词,最朴素的基线 */
    BM25,
    /** 纯向量,语义召回 */
    VECTOR,
    /** BM25 + 向量,RRF 融合 */
    HYBRID,
    /** 融合 + 特征重排 */
    FULL;

    public static RetrievalMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的检索模式: " + raw + "(可选 bm25 / vector / hybrid / full)");
        }
    }
}
