package com.example.rag.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {

    @Test
    @DisplayName("中文按二元组切分,保证不依赖词典也能命中")
    void cjkBigrams() {
        List<String> tokens = Tokenizer.tokenize("循环依赖");
        assertThat(tokens).contains("循环", "环依", "依赖");
    }

    @Test
    @DisplayName("英文驼峰同时产出整体与拆分形式")
    void camelCase() {
        List<String> tokens = Tokenizer.tokenize("SpringBoot");
        assertThat(tokens).contains("springboot", "spring", "boot");
    }

    @Test
    @DisplayName("配置键按分隔符拆开,用户写简称也能命中")
    void dottedKey() {
        List<String> tokens = Tokenizer.tokenize("spring.main.allow-circular-references");
        assertThat(tokens).contains("spring", "main", "allow", "circular", "references");
    }

    @Test
    @DisplayName("高频虚词被过滤,避免 BM25 被 the/a 之类的词拉偏")
    void stopwords() {
        assertThat(Tokenizer.tokenize("the a is")).doesNotContain("the", "a", "is");
    }

    @Test
    @DisplayName("纯标点与空串不产生 token")
    void punctuationOnly() {
        assertThat(Tokenizer.tokenize("!!!???")).isEmpty();
        assertThat(Tokenizer.tokenize("")).isEmpty();
        assertThat(Tokenizer.tokenize(null)).isEmpty();
    }

    @Test
    @DisplayName("中英混排能在正确边界切换")
    void mixed() {
        List<String> tokens = Tokenizer.tokenize("用 Redis 做缓存");
        assertThat(tokens).contains("redis", "缓存");
        // "用" 是单字停用词,不应产生 "用r" 这类跨语言噪声
        assertThat(tokens).noneMatch(t -> t.contains("useredis"));
    }
}
