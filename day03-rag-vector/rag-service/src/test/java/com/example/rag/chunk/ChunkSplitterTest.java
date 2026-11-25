package com.example.rag.chunk;

import com.example.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSplitterTest {

    private ChunkSplitter splitter(int max, int overlap, int min) {
        RagProperties props = new RagProperties();
        props.getChunk().setMaxChars(max);
        props.getChunk().setOverlapChars(overlap);
        props.getChunk().setMinChars(min);
        return new ChunkSplitter(props);
    }

    @Test
    @DisplayName("块长不超过上限")
    void respectsMaxChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是第").append(i).append("句用来测试分块边界的普通文本内容。");
        }
        DocLoader.Doc doc = new DocLoader.Doc("t1", "测试文档", "# 测试文档\n## 小节\n" + sb);
        List<Chunk> chunks = splitter(200, 60, 80).split(doc);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(200));
    }

    @Test
    @DisplayName("相邻块有重叠区,跨块信息不会丢")
    void overlapBetweenChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("第").append(i).append("句的内容在这里,长度控制在二十字左右。");
        }
        DocLoader.Doc doc = new DocLoader.Doc("t2", "测试文档", "## 小节\n" + sb);
        List<Chunk> chunks = splitter(160, 60, 40).split(doc);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1).text();
            String tail = prev.substring(Math.max(0, prev.length() - 20));
            assertThat(chunks.get(i).text()).contains(tail.substring(tail.length() - 10));
        }
    }

    @Test
    @DisplayName("代码围栏整块保留,不会被切成半个代码块")
    void codeFenceIsAtomic() {
        String code = "```java\npublic class A {\n    void run() { }\n}\n```";
        String body = "## 示例\n这是一段说明文字。\n" + code + "\n这是代码之后的说明。";
        DocLoader.Doc doc = new DocLoader.Doc("t3", "测试文档", body);
        List<Chunk> chunks = splitter(400, 40, 20).split(doc);
        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.text()).contains("public class A");
            assertThat(c.text()).contains("```java");
            assertThat(c.text()).contains("}\n```");
        });
    }

    @Test
    @DisplayName("按小节切分,块自带小节名")
    void sectionsAreSeparated() {
        String body = "## 第一节\n内容一。\n## 第二节\n内容二。";
        DocLoader.Doc doc = new DocLoader.Doc("t4", "测试文档", body);
        List<Chunk> chunks = splitter(400, 40, 20).split(doc);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("第一节");
        assertThat(chunks.get(1).section()).isEqualTo("第二节");
        assertThat(chunks.get(0).id()).isEqualTo("t4#00");
    }

    @Test
    @DisplayName("embedText 带标题与小节名,避免无主语的碎片块")
    void embedTextHasContext() {
        DocLoader.Doc doc = new DocLoader.Doc("t5", "网关实践", "## 超时\n它默认是 300 秒。");
        Chunk c = splitter(400, 40, 20).split(doc).get(0);
        assertThat(c.embedText()).startsWith("网关实践 / 超时");
        assertThat(c.citation()).isEqualTo("网关实践 · 超时");
    }
}
