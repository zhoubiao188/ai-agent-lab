package com.example.multiagent.exec;

/**
 * 错误分类是重试策略的地基。分类做错,重试就是随机放大器。
 *
 * <p>经验值:一次线上事故里 92% 的失败集中在 TIMEOUT / RATE_LIMIT / UPSTREAM_5XX 三类,
 * 这三类重试是安全的;剩下 8% 是参数错和业务拒绝,重试只会让用户多等两秒再看到同样的错。</p>
 */
public enum ErrorKind {
    /** 调用方等超时了。上游可能仍在执行 —— 写操作重试前必须确认幂等 */
    TIMEOUT,
    /** 429,通常带 Retry-After */
    RATE_LIMIT,
    /** 5xx */
    UPSTREAM_5XX,
    /** 连接被拒/DNS/断链 */
    CONNECTION,
    /** 参数不符合 schema 或业务前置条件 */
    BAD_ARGUMENT,
    /** 明确的不存在(404),重试无意义 */
    NOT_FOUND,
    /** 业务拒绝:库存不足、超时未支付、金额超限 */
    BUSINESS,
    /** 熔断器打开,直接快速失败 */
    CIRCUIT_OPEN,
    /** 任务预算耗尽 */
    BUDGET_EXCEEDED,
    UNKNOWN;

    public boolean retryable() {
        return this == TIMEOUT || this == RATE_LIMIT || this == UPSTREAM_5XX || this == CONNECTION;
    }
}
