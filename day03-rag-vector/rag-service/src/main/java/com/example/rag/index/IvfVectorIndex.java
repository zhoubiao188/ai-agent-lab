package com.example.rag.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * IVF-Flat:先用 k-means 把向量聚成 nlist 个簇,检索时只扫最近的 nprobe 个簇。
 * <p>搜索量从 n 降到 n·nprobe/nlist,代价是召回率不再是 100%
 * —— 正确答案可能落在没被扫到的簇里。nprobe 就是"速度与召回"的旋钮。</p>
 *
 * <p>这段代码的意义不在于替代 Milvus,而在于让你能亲手看到:
 * 近似索引的召回损失从哪来。线上排查"某条数据永远搜不到"时,
 * 第一个要怀疑的就是它落在了一个没被探测的簇里。</p>
 */
public class IvfVectorIndex implements VectorIndex {

    private final int nlist;
    private final int nprobe;
    private final long seed;

    private String[] ids = new String[0];
    private float[][] vectors = new float[0][];
    private float[][] centroids = new float[0][];
    private List<List<Integer>> lists = new ArrayList<>();

    public IvfVectorIndex(int nlist, int nprobe, long seed) {
        this.nlist = Math.max(1, nlist);
        this.nprobe = Math.max(1, nprobe);
        this.seed = seed;
    }

    @Override
    public void build(List<String> ids, List<float[]> vectors, int dim) {
        this.ids = ids.toArray(new String[0]);
        this.vectors = vectors.toArray(new float[0][]);
        int k = Math.min(nlist, Math.max(1, this.vectors.length));
        this.centroids = kMeans(this.vectors, k, dim);
        this.lists = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            lists.add(new ArrayList<>());
        }
        for (int i = 0; i < this.vectors.length; i++) {
            lists.get(nearestCentroid(this.vectors[i])).add(i);
        }
    }

    @Override
    public List<Scored> search(float[] query, int k) {
        if (ids.length == 0 || centroids.length == 0) {
            return List.of();
        }
        int probe = Math.min(nprobe, centroids.length);
        Integer[] order = new Integer[centroids.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // 按与查询的相似度给簇排序,取前 probe 个
        Arrays.sort(order, (a, b) -> Double.compare(FlatVectorIndex.dot(query, centroids[b]),
                FlatVectorIndex.dot(query, centroids[a])));

        int top = Math.min(k, ids.length);
        PriorityQueue<Scored> heap = new PriorityQueue<>(top + 1, (a, b) -> Double.compare(a.score(), b.score()));
        for (int p = 0; p < probe; p++) {
            for (int idx : lists.get(order[p])) {
                double score = FlatVectorIndex.dot(query, vectors[idx]);
                if (heap.size() < top) {
                    heap.offer(new Scored(ids[idx], score));
                } else if (heap.peek() != null && score > heap.peek().score()) {
                    heap.poll();
                    heap.offer(new Scored(ids[idx], score));
                }
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
        return "ivf(nlist=" + centroids.length + ",nprobe=" + nprobe + ")";
    }

    /** 簇大小分布,用于诊断"所有向量被聚到同一个簇"这类退化情况。 */
    public List<Integer> clusterSizes() {
        List<Integer> sizes = new ArrayList<>();
        lists.forEach(l -> sizes.add(l.size()));
        return sizes;
    }

    private int nearestCentroid(float[] v) {
        int best = 0;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < centroids.length; i++) {
            double s = FlatVectorIndex.dot(v, centroids[i]);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    private float[][] kMeans(float[][] data, int k, int dim) {
        Random random = new Random(seed);
        float[][] cent = new float[k][dim];
        // k-means++ 初始化:第一个随机,后续按与已选中心的距离平方概率采样。
        // 纯随机初始化在聚类数接近样本数时会退化成空簇,这个坑很小但很致命。
        int first = data.length == 0 ? 0 : random.nextInt(data.length);
        if (data.length > 0) {
            System.arraycopy(data[first], 0, cent[0], 0, dim);
        }
        double[] dist = new double[data.length];
        Arrays.fill(dist, Double.MAX_VALUE);
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (int i = 0; i < data.length; i++) {
                double d = 1.0 - FlatVectorIndex.dot(data[i], cent[c - 1]);
                dist[i] = Math.min(dist[i], Math.max(0, d));
                total += dist[i];
            }
            int pick = random.nextInt(Math.max(1, data.length));
            if (total > 1e-9) {
                double target = random.nextDouble() * total;
                double acc = 0;
                for (int i = 0; i < data.length; i++) {
                    acc += dist[i];
                    if (acc >= target) {
                        pick = i;
                        break;
                    }
                }
            }
            if (data.length > 0) {
                System.arraycopy(data[pick], 0, cent[c], 0, dim);
            }
        }

        int[] assign = new int[data.length];
        Arrays.fill(assign, -1);
        for (int iter = 0; iter < 15; iter++) {
            boolean changed = false;
            for (int i = 0; i < data.length; i++) {
                int best = 0;
                double bestScore = -Double.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    double s = FlatVectorIndex.dot(data[i], cent[c]);
                    if (s > bestScore) {
                        bestScore = s;
                        best = c;
                    }
                }
                if (assign[i] != best) {
                    assign[i] = best;
                    changed = true;
                }
            }
            if (!changed && iter > 0) {
                break;
            }
            float[][] sum = new float[k][dim];
            int[] count = new int[k];
            for (int i = 0; i < data.length; i++) {
                int c = assign[i];
                count[c]++;
                for (int d = 0; d < dim; d++) {
                    sum[c][d] += data[i][d];
                }
            }
            for (int c = 0; c < k; c++) {
                if (count[c] > 0) {
                    for (int d = 0; d < dim; d++) {
                        cent[c][d] = sum[c][d] / count[c];
                    }
                }
            }
        }
        return cent;
    }
}
