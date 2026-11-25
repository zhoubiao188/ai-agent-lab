package com.example.rag.search;

import com.example.rag.chunk.Chunk;

/**
 * 带调试信息的一条召回结果。
 * <p>四路分数全部暴露给前端,是为了让"为什么这条排在前面"可以被肉眼检查。
 * RAG 调优最耗时间的不是改代码,是搞清一条结果是怎么被排上来的。</p>
 */
public record ScoredChunk(
        Chunk chunk,
        double score,
        Integer bm25Rank,
        Double bm25Score,
        Integer vectorRank,
        Double vectorScore,
        Double rerankScore) {
}
