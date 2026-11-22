package com.example.agent.config;

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
}
