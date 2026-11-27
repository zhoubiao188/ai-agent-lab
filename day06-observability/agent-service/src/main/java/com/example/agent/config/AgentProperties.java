package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 单次工具调用超时。真实系统里这个值由下游 P99 决定,不是拍脑袋。 */
    private long toolTimeoutMs = 400;
    /** 单步最多尝试几次(含首次)。 */
    private int maxAttempts = 3;
    /** 单步预算:超时 + 退避 + 最后一次尝试都从这里扣。 */
    private long stepBudgetMs = 620;
    /** 整个运行的预算。 */
    private long runBudgetMs = 6000;
    private long backoffBaseMs = 40;
    private long backoffMaxMs = 240;
    private int parallelism = 4;
    private int recallTopK = 5;

    /** mock 模型的计价(微元/token),用来把成本算成钱,而不是只报 token。 */
    private long llmInPriceMicros = 1;
    private long llmOutPriceMicros = 4;

    private int benchIncidents = 200;
    private long benchSeed = 20261127;

    public long getToolTimeoutMs() {
        return toolTimeoutMs;
    }

    public void setToolTimeoutMs(long toolTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getStepBudgetMs() {
        return stepBudgetMs;
    }

    public void setStepBudgetMs(long stepBudgetMs) {
        this.stepBudgetMs = stepBudgetMs;
    }

    public long getRunBudgetMs() {
        return runBudgetMs;
    }

    public void setRunBudgetMs(long runBudgetMs) {
        this.runBudgetMs = runBudgetMs;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public long getBackoffMaxMs() {
        return backoffMaxMs;
    }

    public void setBackoffMaxMs(long backoffMaxMs) {
        this.backoffMaxMs = backoffMaxMs;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public int getRecallTopK() {
        return recallTopK;
    }

    public void setRecallTopK(int recallTopK) {
        this.recallTopK = recallTopK;
    }

    public long getLlmInPriceMicros() {
        return llmInPriceMicros;
    }

    public void setLlmInPriceMicros(long llmInPriceMicros) {
        this.llmInPriceMicros = llmInPriceMicros;
    }

    public long getLlmOutPriceMicros() {
        return llmOutPriceMicros;
    }

    public void setLlmOutPriceMicros(long llmOutPriceMicros) {
        this.llmOutPriceMicros = llmOutPriceMicros;
    }

    public int getBenchIncidents() {
        return benchIncidents;
    }

    public void setBenchIncidents(int benchIncidents) {
        this.benchIncidents = benchIncidents;
    }

    public long getBenchSeed() {
        return benchSeed;
    }

    public void setBenchSeed(long benchSeed) {
        this.benchSeed = benchSeed;
    }
}
