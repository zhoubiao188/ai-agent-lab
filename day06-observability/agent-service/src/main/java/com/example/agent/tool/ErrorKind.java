package com.example.agent.tool;

/** 工具错误分类。分类决定"能不能重试",这一步做错,后面所有重试策略都是错的。 */
public enum ErrorKind {

    TIMEOUT("超时", true),
    UPSTREAM_5XX("上游 5xx", true),
    RATE_LIMIT("限流", true),
    /** 参数不合法。重试一万次也是同样的错,必须回到决策层改参数。 */
    BAD_ARGUMENT("参数非法", false),
    /** 业务拒绝(余额不足、超过退款期限)。重试无意义。 */
    BUSINESS("业务拒绝", false),
    /** 依赖的前置步骤失败。 */
    DEPENDENCY_FAILED("依赖失败", false);

    private final String label;
    private final boolean retryable;

    ErrorKind(String label, boolean retryable) {
        this.label = label;
        this.retryable = retryable;
    }

    public String label() {
        return label;
    }

    public boolean retryable() {
        return retryable;
    }
}
