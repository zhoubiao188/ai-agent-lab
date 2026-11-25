package com.example.rag.config;

import com.example.rag.chunk.ChunkSplitter;
import com.example.rag.chunk.DocLoader;
import com.example.rag.embed.CachedEmbeddingModel;
import com.example.rag.embed.EmbeddingModel;
import com.example.rag.embed.HashingEmbeddingModel;
import com.example.rag.embed.OpenAiEmbeddingModel;
import com.example.rag.index.FlatVectorIndex;
import com.example.rag.index.IvfVectorIndex;
import com.example.rag.index.VectorIndex;
import com.example.rag.llm.LlmClient;
import com.example.rag.llm.MockLlmClient;
import com.example.rag.llm.OpenAiCompatLlmClient;
import com.example.rag.rag.Corpus;
import com.example.rag.rerank.FeatureReranker;
import com.example.rag.search.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配。注意 {@link Corpus} 在 factory method 里就完成了全部索引构建,
 * 这样任何构建异常都会让应用启动失败,而不是在第一个请求进来时抛 500。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public EmbeddingModel embeddingModel(RagProperties props) {
        RagProperties.Embed embed = props.getEmbed();
        EmbeddingModel raw;
        if ("openai".equalsIgnoreCase(embed.getProvider())) {
            if (embed.getApiKey() == null || embed.getApiKey().isBlank()) {
                throw new IllegalStateException("rag.embed.provider=openai 时必须配置 RAG_EMBED_API_KEY");
            }
            raw = new OpenAiEmbeddingModel(embed.getBaseUrl(), embed.getApiKey(), embed.getModel(),
                    embed.getBatchSize(), embed.getDim());
        } else {
            raw = new HashingEmbeddingModel(embed.getDim());
        }
        log.info("向量模型: {} dim={}", raw.name(), raw.dim());
        return new CachedEmbeddingModel(raw, embed.getCacheSize());
    }

    @Bean
    public Bm25Index bm25Index() {
        return new Bm25Index();
    }

    @Bean
    public VectorIndex vectorIndex(RagProperties props) {
        RagProperties.Index idx = props.getIndex();
        if ("ivf".equalsIgnoreCase(idx.getType())) {
            return new IvfVectorIndex(idx.getNlist(), idx.getNprobe(), 42L);
        }
        return new FlatVectorIndex();
    }

    @Bean
    public FeatureReranker featureReranker(Bm25Index bm25Index) {
        return new FeatureReranker(bm25Index);
    }

    @Bean
    public Corpus corpus(RagProperties props, DocLoader loader, ChunkSplitter splitter,
                         EmbeddingModel embeddingModel, Bm25Index bm25Index, VectorIndex vectorIndex) {
        Corpus corpus = new Corpus(props, loader, splitter, embeddingModel, bm25Index, vectorIndex);
        log.info("索引构建完成: {} 文档 / {} 块 / 向量化 {}ms / 总计 {}ms / 索引 {}",
                corpus.docs().size(), corpus.chunks().size(), corpus.embedMillis(),
                corpus.buildMillis(), vectorIndex.type());
        return corpus;
    }

    @Bean
    public LlmClient llmClient(RagProperties props) {
        RagProperties.Llm llm = props.getLlm();
        if ("openai".equalsIgnoreCase(llm.getProvider())) {
            if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
                throw new IllegalStateException("rag.llm.provider=openai 时必须配置 RAG_LLM_API_KEY");
            }
            return new OpenAiCompatLlmClient(llm.getBaseUrl(), llm.getApiKey(), llm.getModel(), llm.getTemperature());
        }
        return new MockLlmClient(12);
    }
}
