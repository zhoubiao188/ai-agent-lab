package com.example.rag.search;

import com.example.rag.chunk.Chunk;
import com.example.rag.index.Scored;
import com.example.rag.text.Tokenizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * BM25 倒排索引。
 * <p>公式:idf · tf·(k1+1) / (tf + k1·(1 - b + b·dl/avgdl)),
 * idf 用 log(1 + (N-df+0.5)/(df+0.5)) 这个恒正变体,避免高频词出现负分。</p>
 *
 * <p>它在 RAG 里被严重低估。术语、错误码、配置项、类名这类查询,
 * BM25 的准确率经常高于向量检索,而且是零成本、可解释、可增量更新。</p>
 */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private List<Chunk> chunks = List.of();
    private Map<String, List<int[]>> postings = new HashMap<>();
    private Map<String, Integer> docFreq = new HashMap<>();
    private int[] docLen = new int[0];
    private double avgDocLen = 1.0;

    public void build(List<Chunk> chunks) {
        this.chunks = chunks;
        this.postings = new HashMap<>();
        this.docFreq = new HashMap<>();
        this.docLen = new int[chunks.size()];
        long totalLen = 0;
        for (int i = 0; i < chunks.size(); i++) {
            List<String> tokens = Tokenizer.tokenize(chunks.get(i).embedText());
            Map<String, Integer> tf = new HashMap<>(tokens.size() * 2);
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            docLen[i] = tokens.size();
            totalLen += tokens.size();
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
                postings.computeIfAbsent(term, x -> new ArrayList<>()).add(new int[]{i, tf.get(term)});
            }
        }
        this.avgDocLen = chunks.isEmpty() ? 1.0 : Math.max(1.0, (double) totalLen / chunks.size());
    }

    public List<Scored> search(String query, int k) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        double[] acc = new double[chunks.size()];
        for (String term : Tokenizer.distinct(query)) {
            List<int[]> plist = postings.get(term);
            if (plist == null) {
                continue;
            }
            double idf = idf(term);
            for (int[] p : plist) {
                int doc = p[0];
                int tf = p[1];
                double dl = docLen[doc];
                acc[doc] += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgDocLen));
            }
        }
        int top = Math.min(k, chunks.size());
        PriorityQueue<Scored> heap = new PriorityQueue<>(top + 1, (a, b) -> Double.compare(a.score(), b.score()));
        for (int i = 0; i < acc.length; i++) {
            if (acc[i] <= 0) {
                continue;
            }
            Scored s = new Scored(chunks.get(i).id(), acc[i]);
            if (heap.size() < top) {
                heap.offer(s);
            } else if (heap.peek() != null && s.score() > heap.peek().score()) {
                heap.poll();
                heap.offer(s);
            }
        }
        List<Scored> out = new ArrayList<>(heap);
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    public double idf(String term) {
        int n = chunks.size();
        int df = docFreq.getOrDefault(term, 0);
        return Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
    }

    public int docFreq(String term) {
        return docFreq.getOrDefault(term, 0);
    }

    public int vocabSize() {
        return postings.size();
    }

    public double avgDocLen() {
        return avgDocLen;
    }

    public int docLength(int docIdx) {
        return docIdx >= 0 && docIdx < docLen.length ? docLen[docIdx] : 0;
    }
}
