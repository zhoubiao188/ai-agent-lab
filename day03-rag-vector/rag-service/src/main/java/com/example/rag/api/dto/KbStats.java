package com.example.rag.api.dto;

import java.util.List;
import java.util.Map;

/** 知识库概况,前端首屏用它渲染指标卡。 */
public record KbStats(
        int docs,
        int chunks,
        int vocab,
        double avgDocLen,
        int dim,
        String embedding,
        String indexType,
        long buildMillis,
        long embedMillis,
        double cacheHitRate,
        String llm,
        List<DocStat> byDoc) {

    public record DocStat(String docId, String title, int chunks) {
    }

    public static KbStats of(int docs, int chunks, int vocab, double avgDocLen, int dim, String embedding,
                             String indexType, long buildMillis, long embedMillis, double cacheHitRate,
                             String llm, Map<String, Integer> byDoc, Map<String, String> titles) {
        List<DocStat> list = byDoc.entrySet().stream()
                .map(e -> new DocStat(e.getKey(), titles.getOrDefault(e.getKey(), e.getKey()), e.getValue()))
                .toList();
        return new KbStats(docs, chunks, vocab, avgDocLen, dim, embedding, indexType,
                buildMillis, embedMillis, cacheHitRate, llm, list);
    }
}
