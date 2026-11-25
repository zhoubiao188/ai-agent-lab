package com.example.rag.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorIndexTest {

    private static final int DIM = 32;

    /** 造 10 个簇的模拟数据:每个簇有一个中心,样本在中心附近抖动。 */
    private record Data(List<String> ids, List<float[]> vectors) {
    }

    private Data clustered(int perCluster) {
        Random random = new Random(7);
        List<String> ids = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        for (int c = 0; c < 10; c++) {
            float[] center = new float[DIM];
            for (int d = 0; d < DIM; d++) {
                center[d] = (float) random.nextGaussian();
            }
            normalize(center);
            for (int i = 0; i < perCluster; i++) {
                float[] v = new float[DIM];
                for (int d = 0; d < DIM; d++) {
                    v[d] = center[d] + (float) random.nextGaussian() * 0.05f;
                }
                normalize(v);
                ids.add("c" + c + "-" + i);
                vectors.add(v);
            }
        }
        return new Data(ids, vectors);
    }

    private static void normalize(float[] v) {
        double s = 0;
        for (float x : v) {
            s += (double) x * x;
        }
        float inv = (float) (1.0 / Math.sqrt(s));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    @Test
    @DisplayName("Flat 检索的 top-1 就是查询向量本身")
    void flatFindsExactVector() {
        Data data = clustered(20);
        FlatVectorIndex idx = new FlatVectorIndex();
        idx.build(data.ids(), data.vectors(), DIM);

        List<Scored> hits = idx.search(data.vectors().get(37), 5);
        assertThat(hits).hasSize(5);
        assertThat(hits.get(0).chunkId()).isEqualTo(data.ids().get(37));
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
    }

    @Test
    @DisplayName("IVF 返回同样条数,且查询精确向量时也能命中自己")
    void ivfFindsExactVector() {
        Data data = clustered(20);
        IvfVectorIndex idx = new IvfVectorIndex(10, 3, 42L);
        idx.build(data.ids(), data.vectors(), DIM);

        List<Scored> hits = idx.search(data.vectors().get(37), 5);
        assertThat(hits).hasSize(5);
        assertThat(hits.get(0).chunkId()).isEqualTo(data.ids().get(37));
    }

    @Test
    @DisplayName("IVF 的召回损失可量化:nprobe 越大越接近暴力检索")
    void ivfRecallIncreasesWithNprobe() {
        Data data = clustered(20);
        FlatVectorIndex flat = new FlatVectorIndex();
        flat.build(data.ids(), data.vectors(), DIM);

        double small = recall(flat, new IvfVectorIndex(10, 1, 42L), data);
        double large = recall(flat, new IvfVectorIndex(10, 5, 42L), data);

        assertThat(large).isGreaterThanOrEqualTo(small);
        assertThat(large).isGreaterThan(0.8);
    }

    /** 用 flat 的 top-10 作为"真值",看 ivf 能覆盖多少。 */
    private double recall(VectorIndex flat, VectorIndex ivf, Data data) {
        ivf.build(data.ids(), data.vectors(), DIM);
        int total = 0;
        int covered = 0;
        for (int q = 0; q < 20; q++) {
            float[] query = data.vectors().get(q * 7);
            Set<String> truth = new HashSet<>();
            flat.search(query, 10).forEach(s -> truth.add(s.chunkId()));
            Set<String> got = new HashSet<>();
            ivf.search(query, 10).forEach(s -> got.add(s.chunkId()));
            total += truth.size();
            covered += (int) truth.stream().filter(got::contains).count();
        }
        return (double) covered / total;
    }

    @Test
    @DisplayName("维度不一致必须直接失败,不能静默算错")
    void dimensionMismatchFails() {
        FlatVectorIndex idx = new FlatVectorIndex();
        assertThatThrownBy(() -> idx.build(List.of("a"), List.of(new float[8]), 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");
    }

    @Test
    @DisplayName("空索引返回空结果")
    void emptyIndex() {
        FlatVectorIndex idx = new FlatVectorIndex();
        idx.build(List.of(), List.of(), 8);
        assertThat(idx.search(new float[8], 3)).isEmpty();
        assertThat(idx.size()).isZero();
    }
}
