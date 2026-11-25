package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 所有可调参数集中在一处。RAG 系统的效果几乎全靠这些数字,
 * 散落在各个类里的常量是最难维护的东西。
 */
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private final Chunk chunk = new Chunk();
    private final Embed embed = new Embed();
    private final Index index = new Index();
    private final Search search = new Search();
    private final Llm llm = new Llm();
    private final Eval eval = new Eval();

    public Chunk getChunk() { return chunk; }
    public Embed getEmbed() { return embed; }
    public Index getIndex() { return index; }
    public Search getSearch() { return search; }
    public Llm getLlm() { return llm; }
    public Eval getEval() { return eval; }

    /** 分块参数:块长、重叠、最小块长。改任意一个都必须全量重建索引。 */
    public static class Chunk {
        /** 单块最大字符数。中文技术文档的经验区间 300~600。 */
        private int maxChars = 520;
        /** 相邻块的重叠字符数,用来兜住被切断的跨块信息。 */
        private int overlapChars = 90;
        /** 小于该长度的块会尝试并入前一块,避免产生"只有一句话"的碎片。 */
        private int minChars = 140;

        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getOverlapChars() { return overlapChars; }
        public void setOverlapChars(int overlapChars) { this.overlapChars = overlapChars; }
        public int getMinChars() { return minChars; }
        public void setMinChars(int minChars) { this.minChars = minChars; }
    }

    /** 向量化参数。mock 是本地确定性实现,openai 兼容任意 /embeddings 接口。 */
    public static class Embed {
        private String provider = "mock";
        private int dim = 512;
        private int batchSize = 16;
        /** 向量 LRU 缓存条数。线上重复 query 极多,命中率通常能到 40% 以上。 */
        private int cacheSize = 4096;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "text-embedding-3-small";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getDim() { return dim; }
        public void setDim(int dim) { this.dim = dim; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public int getCacheSize() { return cacheSize; }
        public void setCacheSize(int cacheSize) { this.cacheSize = cacheSize; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    /** 向量索引参数。flat 暴力检索做基线,ivf 用聚类切分搜索空间。 */
    public static class Index {
        private String type = "flat";
        private int nlist = 16;
        private int nprobe = 4;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public int getNlist() { return nlist; }
        public void setNlist(int nlist) { this.nlist = nlist; }
        public int getNprobe() { return nprobe; }
        public void setNprobe(int nprobe) { this.nprobe = nprobe; }
    }

    /** 检索与融合参数。 */
    public static class Search {
        /** BM25 召回数。召回阶段宁多不漏,后面还有重排。 */
        private int bm25K = 30;
        private int vectorK = 30;
        /** RRF 的平滑常数,论文默认 60。越大越削弱头部排名差异。 */
        private int rrfK = 60;
        private double bm25Weight = 1.0;
        private double vectorWeight = 1.0;
        /** 重排时特征分的权重,剩余权重给融合分。 */
        private double rerankAlpha = 0.2;
        /** 默认返回条数。 */
        private int topK = 8;

        public int getBm25K() { return bm25K; }
        public void setBm25K(int bm25K) { this.bm25K = bm25K; }
        public int getVectorK() { return vectorK; }
        public void setVectorK(int vectorK) { this.vectorK = vectorK; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }
        public double getBm25Weight() { return bm25Weight; }
        public void setBm25Weight(double bm25Weight) { this.bm25Weight = bm25Weight; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
        public double getRerankAlpha() { return rerankAlpha; }
        public void setRerankAlpha(double rerankAlpha) { this.rerankAlpha = rerankAlpha; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    /** 生成参数。mock 不需要 Key,便于离线跑通全链路。 */
    public static class Llm {
        private String provider = "mock";
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private double temperature = 0.2;
        /** 拼进上下文的块数。给太多块反而会把正确块淹没。 */
        private int contextTopK = 4;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getContextTopK() { return contextTopK; }
        public void setContextTopK(int contextTopK) { this.contextTopK = contextTopK; }
    }

    /** 评测参数。 */
    public static class Eval {
        /** 评测集文件位置。 */
        private String queries = "eval/queries.json";
        /** 计算指标时看的深度。 */
        private int depth = 10;

        public String getQueries() { return queries; }
        public void setQueries(String queries) { this.queries = queries; }
        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }
    }
}
