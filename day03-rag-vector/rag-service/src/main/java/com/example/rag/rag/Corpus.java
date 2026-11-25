package com.example.rag.rag;

import com.example.rag.chunk.Chunk;
import com.example.rag.chunk.ChunkSplitter;
import com.example.rag.chunk.DocLoader;
import com.example.rag.config.RagProperties;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.index.VectorIndex;
import com.example.rag.search.Bm25Index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期构建的不可变语料库。
 * <p>把"加载 → 分块 → 向量化 → 建索引"放在一个构造函数里,好处是构建失败会在启动期暴露,
 * 而不是等到第一个用户请求进来才 500。代价是启动变慢,所以真实项目里这一步应该是
 * 独立的重建任务 + 灰度切换索引,而不是每次启动都全量重建。</p>
 */
public class Corpus {

    private final List<DocLoader.Doc> docs;
    private final List<Chunk> chunks;
    private final Map<String, Chunk> byId;
    private final Map<String, Integer> docChunkCount;
    private final long buildMillis;
    private final long embedMillis;

    public Corpus(RagProperties props, DocLoader loader, ChunkSplitter splitter,
                  EmbeddingModel embedding, Bm25Index bm25, VectorIndex vectorIndex) {
        long t0 = System.currentTimeMillis();
        this.docs = loader.load("classpath:kb/*.md");
        if (docs.isEmpty()) {
            throw new IllegalStateException("知识库为空,检查 classpath:kb/*.md 是否存在");
        }

        List<Chunk> all = new ArrayList<>();
        for (DocLoader.Doc doc : docs) {
            all.addAll(splitter.split(doc));
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("分块结果为空");
        }
        this.chunks = List.copyOf(all);

        Map<String, Chunk> map = new LinkedHashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            map.put(c.id(), c);
            count.merge(c.docId(), 1, Integer::sum);
        }
        this.byId = Map.copyOf(map);
        this.docChunkCount = Map.copyOf(count);

        long t1 = System.currentTimeMillis();
        List<String> ids = new ArrayList<>(chunks.size());
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            ids.add(c.id());
            vectors.add(embedding.embed(c.embedText()));
        }
        this.embedMillis = System.currentTimeMillis() - t1;

        vectorIndex.build(ids, vectors, embedding.dim());
        bm25.build(chunks);
        this.buildMillis = System.currentTimeMillis() - t0;
    }

    public List<DocLoader.Doc> docs() { return docs; }

    public List<Chunk> chunks() { return chunks; }

    public Chunk byId(String id) { return byId.get(id); }

    public Map<String, Integer> docChunkCount() { return docChunkCount; }

    public long buildMillis() { return buildMillis; }

    public long embedMillis() { return embedMillis; }
}
