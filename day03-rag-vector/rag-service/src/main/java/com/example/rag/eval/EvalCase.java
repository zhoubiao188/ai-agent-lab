package com.example.rag.eval;

import java.util.List;

/**
 * 一条评测样本。
 *
 * @param type terminology / paraphrase / multi-hop / code / english 等类别,
 *             分类统计才能看出"混合检索到底救了哪一类查询"
 */
public record EvalCase(String id, String type, String query, List<Gold> golds) {
}
