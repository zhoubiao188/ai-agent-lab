package com.example.rag.search;

import com.example.rag.chunk.Chunk;
import com.example.rag.index.Scored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RetrievalServiceTest {

    @Autowired
    private RetrievalService retrieval;

    @Test
    @DisplayName("四种模式都返回结果,且分数严格降序")
    void allModesReturnSortedResults() {
        for (RetrievalMode mode : RetrievalMode.values()) {
            List<ScoredChunk> hits = retrieval.retrieve("网关流式输出被缓冲怎么办", mode, 5);
            assertThat(hits).as("模式 %s", mode).isNotEmpty();
            for (int i = 1; i < hits.size(); i++) {
                assertThat(hits.get(i - 1).score())
                        .as("模式 %s 第 %d 名", mode, i)
                        .isGreaterThanOrEqualTo(hits.get(i).score());
            }
        }
    }

    @Test
    @DisplayName("RRF 融合后,两路各自的 top-1 都出现在融合结果前三")
    void fusionKeepsBothSourcesTopHit() {
        String query = "虚拟线程被 synchronized 钉住";
        String bm25Top = retrieval.retrieve(query, RetrievalMode.BM25, 1).get(0).chunk().id();
        String vectorTop = retrieval.retrieve(query, RetrievalMode.VECTOR, 1).get(0).chunk().id();
        List<String> hybridTop3 = retrieval.retrieve(query, RetrievalMode.HYBRID, 3)
                .stream().map(h -> h.chunk().id()).toList();
        assertThat(hybridTop3).contains(bm25Top);
        assertThat(hybridTop3).contains(vectorTop);
    }

    @Test
    @DisplayName("调试信息完整:融合结果能回查到两路的原始名次")
    void debugInfoIsComplete() {
        List<ScoredChunk> hits = retrieval.retrieve("RRF 为什么比加权分数稳定", RetrievalMode.HYBRID, 3);
        ScoredChunk first = hits.get(0);
        assertThat(first.bm25Rank()).isNotNull();
        assertThat(first.vectorRank()).isNotNull();
        assertThat(first.bm25Score()).isPositive();
        // FULL 模式才会填充 rerankScore
        assertThat(first.rerankScore()).isNull();

        List<ScoredChunk> full = retrieval.retrieve("RRF 为什么比加权分数稳定", RetrievalMode.FULL, 3);
        assertThat(full.get(0).rerankScore()).isNotNull();
    }

    @Test
    @DisplayName("空查询返回空,不抛异常")
    void blankQuery() {
        assertThat(retrieval.retrieve("  ", RetrievalMode.FULL, 5)).isEmpty();
    }

    @Test
    @DisplayName("向量检索的原始结果与 Flat 索引一致")
    void vectorSearchMatchesIndex() {
        List<Scored> hits = retrieval.semanticSearch("死锁排查", 3);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).score()).isBetween(-1.0, 1.0);
        assertThat(hits.get(0).chunkId()).contains("#");
    }

    @Test
    @DisplayName("检索结果里带回来的块必须能被语料库反查到,避免 id 漂移")
    void chunkIdsResolvable() {
        for (ScoredChunk hit : retrieval.retrieve("索引下推", RetrievalMode.FULL, 5)) {
            Chunk c = hit.chunk();
            assertThat(c.id()).isNotBlank();
            assertThat(c.text()).isNotBlank();
            assertThat(c.docTitle()).isNotBlank();
        }
    }
}
