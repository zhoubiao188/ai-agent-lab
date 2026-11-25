package com.example.rag.embed;

/**
 * 向量化抽象。
 * <p>只暴露两个方法是有意为之:批量接口和维度。任何多余的能力(比如 token 计数)
 * 都会让替换模型时被迫改业务代码。</p>
 */
public interface EmbeddingModel {

    /** 向量维度。构建索引时校验,维度不一致必须直接失败,而不是静默算错。 */
    int dim();

    /** 模型标识,写进索引元数据,换模型时用来判断是否需要全量重建。 */
    String name();

    /** 单条向量化,返回已做 L2 归一化的向量(余弦相似度退化为点积,省一半计算)。 */
    float[] embed(String text);
}
