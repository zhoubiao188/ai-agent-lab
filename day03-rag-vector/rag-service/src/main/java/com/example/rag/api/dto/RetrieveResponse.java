package com.example.rag.api.dto;

import com.example.rag.search.ScoredChunk;

import java.util.List;

/** 检索结果包装,带上耗时便于前端展示性能,也便于出问题时报给开发。 */
public record RetrieveResponse(String query, String mode, long tookMs, int total, List<ScoredChunk> hits) {
}
