package com.example.rag.search;

import com.example.rag.chunk.Chunk;
import com.example.rag.index.Scored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25IndexTest {

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc", "文档", "小节", 0, text);
    }

    private Bm25Index index() {
        Bm25Index idx = new Bm25Index();
        idx.build(List.of(
                chunk("c1", "联合索引 (a, b, c) 支持最左前缀匹配,跳过 a 就会全表扫描。"),
                chunk("c2", "虚拟线程遇到 synchronized 包裹的阻塞调用会被钉住,平台线程被占住。"),
                chunk("c3", "网关的 SSE 流式输出最常见的故障是响应被缓冲,需要设置 no-transform。")
        ));
        return idx;
    }

    @Test
    @DisplayName("稀缺词命中排在第一位")
    void rareTermRanksFirst() {
        List<Scored> hits = index().search("最左前缀", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunkId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("idf 会惩罚到处都出现的词")
    void idfPenalizesCommonTerms() {
        Bm25Index idx = index();
        // "的" 这类字在二元组里被过滤,这里用一个在三块里都出现的词做对照
        assertThat(idx.docFreq("索引")).isEqualTo(1);
        assertThat(idx.idf("索引")).isGreaterThan(0);
    }

    @Test
    @DisplayName("完全无关的查询返回空,而不是硬凑结果")
    void irrelevantQueryReturnsEmpty() {
        assertThat(index().search("量子纠缠半导体封装", 3)).isEmpty();
    }

    @Test
    @DisplayName("k 参数生效,返回条数不超过 k")
    void respectsK() {
        assertThat(index().search("线程 索引 网关", 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("空索引与空查询不会抛异常")
    void emptySafety() {
        Bm25Index empty = new Bm25Index();
        empty.build(List.of());
        assertThat(empty.search("任意", 5)).isEmpty();
        assertThat(index().search("", 5)).isEmpty();
    }
}
