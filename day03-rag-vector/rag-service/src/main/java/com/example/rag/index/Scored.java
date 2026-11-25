package com.example.rag.index;

/** 一条向量检索结果。分数为余弦相似度(向量已归一化,等价于点积)。 */
public record Scored(String chunkId, double score) {
}
