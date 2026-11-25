package com.example.rag.embed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 向量 LRU 缓存装饰器。
 * <p>线上重复 query 的比例远比想象中高(用户会反复问同一句话、前端会重放请求)。
 * 命中一次就省一次网络往返,RAG 的首字延迟立刻好看很多。</p>
 *
 * <p>注意这里是 synchronized 的简单实现:缓存命中在微秒级,锁竞争可以接受。
 * 如果 QPS 真的很高,应换成分段锁或 Caffeine,而不是在这里加复杂度。</p>
 */
public class CachedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final int capacity;
    private final Map<String, float[]> cache;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CachedEmbeddingModel(EmbeddingModel delegate, int capacity) {
        this.delegate = delegate;
        this.capacity = Math.max(16, capacity);
        this.cache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > CachedEmbeddingModel.this.capacity;
            }
        };
    }

    @Override
    public int dim() {
        return delegate.dim();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public float[] embed(String text) {
        String key = text == null ? "" : text;
        synchronized (cache) {
            float[] cached = cache.get(key);
            if (cached != null) {
                hits.increment();
                return cached;
            }
        }
        float[] v = delegate.embed(key);
        synchronized (cache) {
            cache.put(key, v);
        }
        misses.increment();
        return v;
    }

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
