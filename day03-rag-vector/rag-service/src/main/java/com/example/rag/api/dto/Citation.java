package com.example.rag.api.dto;

/** 引用条目。前端拿它渲染角标与侧边出处列表。 */
public record Citation(int index, String chunkId, String title, String section, double score, String snippet) {
}
