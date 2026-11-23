package com.example.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 全部可配置项集中在 {@code agent.*} 前缀下,方便后面接 Nacos/Config Server 时零改动。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 使用的 provider 名称:mock / openai */
    private String provider = "mock";

    /** 单轮对话最多允许的 Agent 迭代次数(防死循环) */
    private int maxSteps = 6;

    /** 单次请求整体超时 */
    private Duration requestTimeout = Duration.ofMinutes(3);

    /** 会话记忆保留的最大消息条数 */
    private int memoryWindow = 20;

    /** 限流:每个客户端每分钟允许的请求数 */
    private int rateLimitPerMinute = 20;

    private final Llm llm = new Llm();
    private final Prompt prompt = new Prompt();
    private final Memory memory = new Memory();

    /**
     * 记忆子系统配置。所有阈值都给了保守默认值,mock 模式下开箱即用。
     */
    public static class Memory {
        /** memory:进程内(默认,零依赖);redis:跨实例持久化 */
        private String store = "memory";

        /** 会话与归档的存活时间,每次写入都会续期 */
        private Duration ttl = Duration.ofDays(7);

        /** 单次请求允许占用的"记忆预算"(摘要 + 活跃窗口),按 token 估算 */
        private int budgetTokens = 6000;

        /** 水位线:超过 budget * threshold 就触发压缩 */
        private double compactThreshold = 0.72;

        /** 压缩后必须保留的最近上下文 token 数 */
        private int keepTailTokens = 1400;

        /** 压缩后至少保留的完整轮次数,防止"刚聊完就被压掉" */
        private int keepMinSegments = 2;

        /** 活跃窗口消息条数上限,超过即压缩(与 token 水位取先到者) */
        private int maxMessages = 36;

        /** 摘要正文长度上限(字符),超了按句截断 */
        private int summaryMaxChars = 700;

        /** 归档召回条数 */
        private int recallTopK = 3;

        /** 归档召回最低得分,低于此值视为不相关 */
        private double recallMinScore = 0.15;

        /** 归档最多保留的消息条数 */
        private int archiveMaxMessages = 400;

        /** 单次摘要调用的超时,超时即降级为本地兜底摘要 */
        private Duration summaryTimeout = Duration.ofSeconds(20);

        /** 连续失败多少次后熔断(期间不再调用摘要模型,直接用兜底摘要) */
        private int breakerFailures = 3;

        /** 熔断冷却时间 */
        private Duration breakerCooldown = Duration.ofMinutes(5);

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getBudgetTokens() {
            return budgetTokens;
        }

        public void setBudgetTokens(int budgetTokens) {
            this.budgetTokens = budgetTokens;
        }

        public double getCompactThreshold() {
            return compactThreshold;
        }

        public void setCompactThreshold(double compactThreshold) {
            this.compactThreshold = compactThreshold;
        }

        public int getKeepTailTokens() {
            return keepTailTokens;
        }

        public void setKeepTailTokens(int keepTailTokens) {
            this.keepTailTokens = keepTailTokens;
        }

        public int getKeepMinSegments() {
            return keepMinSegments;
        }

        public void setKeepMinSegments(int keepMinSegments) {
            this.keepMinSegments = keepMinSegments;
        }

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public int getSummaryMaxChars() {
            return summaryMaxChars;
        }

        public void setSummaryMaxChars(int summaryMaxChars) {
            this.summaryMaxChars = summaryMaxChars;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public double getRecallMinScore() {
            return recallMinScore;
        }

        public void setRecallMinScore(double recallMinScore) {
            this.recallMinScore = recallMinScore;
        }

        public int getArchiveMaxMessages() {
            return archiveMaxMessages;
        }

        public void setArchiveMaxMessages(int archiveMaxMessages) {
            this.archiveMaxMessages = archiveMaxMessages;
        }

        public Duration getSummaryTimeout() {
            return summaryTimeout;
        }

        public void setSummaryTimeout(Duration summaryTimeout) {
            this.summaryTimeout = summaryTimeout;
        }

        public int getBreakerFailures() {
            return breakerFailures;
        }

        public void setBreakerFailures(int breakerFailures) {
            this.breakerFailures = breakerFailures;
        }

        public Duration getBreakerCooldown() {
            return breakerCooldown;
        }

        public void setBreakerCooldown(Duration breakerCooldown) {
            this.breakerCooldown = breakerCooldown;
        }
    }

    public static class Llm {
        /** OpenAI 兼容网关地址,如 https://api.deepseek.com/v1 */
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private double temperature = 0.3;
        private Duration connectTimeout = Duration.ofSeconds(10);
        /** 流式读取超时(两个数据帧之间的最大间隔) */
        private Duration readTimeout = Duration.ofSeconds(90);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Prompt {
        private String system = """
                你是一个严谨的中文技术助理,运行在一个 Spring Boot 后端服务里。

                你可以使用工具来获取实时信息或执行动作。使用规则:
                1. 需要实时数据(时间、天气)、精确计算、或内部知识库内容时,必须先调用工具,不要凭记忆编造。
                2. 一次可以调用多个工具;拿到工具结果后,用中文简洁地总结给用户。
                3. 工具失败时如实说明失败原因,并给出替代建议,不要伪造结果。
                4. 回答使用 Markdown,代码块标注语言。不要输出你的思考过程。

                关于记忆:
                5. 上下文里可能出现【长期记忆·会话摘要】【必须记住的关键信息】【更早对话的相关片段】,
                   它们是更早轮次的压缩结果,可直接引用,但不要向用户解释"这是摘要"。
                6. 摘要与最近消息冲突时,一律以最近消息为准;关键数字、单号、日期必须原文照抄,不得四舍五入。
                7. 用户提到"刚才说的那个"时,先在记忆区块里找指代对象;找不到就明确问清楚,不要猜。
                """;
        /** 是否把每次请求可用的工具清单注入到 system 后面(便于模型感知能力边界) */
        private boolean injectToolCatalog = true;

        public String getSystem() {
            return system;
        }

        public void setSystem(String system) {
            this.system = system;
        }

        public boolean isInjectToolCatalog() {
            return injectToolCatalog;
        }

        public void setInjectToolCatalog(boolean injectToolCatalog) {
            this.injectToolCatalog = injectToolCatalog;
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(int memoryWindow) {
        this.memoryWindow = memoryWindow;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public Llm getLlm() {
        return llm;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Memory getMemory() {
        return memory;
    }
}
