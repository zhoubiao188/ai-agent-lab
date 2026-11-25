package com.example.rag.index;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 暴力检索(Flat)。O(n·d),没有近似,召回率 100%,是所有近似索引的评测基线。
 * <p>十万条以内的知识库直接用它就够了 —— 一次全量点积在一毫秒量级,
 * 上 HNSW 的复杂度收益要等到数据量真正变大之后才成立。</p>
 */
public class FlatVectorIndex implements VectorIndex {

    private String[] ids = new String[0];
    private float[][] vectors = new float[0][];

    @Override
    public void build(List<String> ids, List<float[]> vectors, int dim) {
        this.ids = ids.toArray(new String[0]);
        this.vectors = vectors.toArray(new float[0][]);
        for (float[] v : this.vectors) {
            if (v.length != dim) {
                throw new IllegalArgumentException("向量维度不一致: " + v.length + " != " + dim);
            }
        }
    }

    @Override
    public List<Scored> search(float[] query, int k) {
        if (ids.length == 0) {
            return List.of();
        }
        int top = Math.min(k, ids.length);
        PriorityQueue<Scored> heap = new PriorityQueue<>(top + 1, (a, b) -> Double.compare(a.score(), b.score()));
        for (int i = 0; i < vectors.length; i++) {
            double score = dot(query, vectors[i]);
            if (heap.size() < top) {
                heap.offer(new Scored(ids[i], score));
            } else if (heap.peek() != null && score > heap.peek().score()) {
                heap.poll();
                heap.offer(new Scored(ids[i], score));
            }
        }
        List<Scored> out = new ArrayList<>(heap);
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    @Override
    public int size() {
        return ids.length;
    }

    @Override
    public String type() {
        return "flat";
    }

    static double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
