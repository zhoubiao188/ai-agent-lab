package com.example.rag.index;

import java.util.List;

/** 向量索引抽象。生产上这里就是 pgvector / Milvus / Qdrant 的客户端。 */
public interface VectorIndex {

    void build(List<String> ids, List<float[]> vectors, int dim);

    List<Scored> search(float[] query, int k);

    int size();

    String type();
}
