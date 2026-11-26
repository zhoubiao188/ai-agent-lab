package com.example.tool.exec;

/**
 * 工具层统一异常。带 kind 是为了让上层能做决策,而不是被迫去解析 message 字符串。
 *
 * <p>反例(踩过):早期用 {@code RuntimeException("timeout")} 区分错误,
 * 结果上游把超时文案改成"请求超时(504)"之后,重试策略静默失效——
 * 所有超时都被当成 UNKNOWN 不再重试,成功率从 97% 掉到 78%,监控上却什么都看不出来。</p>
 */
public class ToolException extends RuntimeException {

    private final ErrorKind kind;
    private final long retryAfterMs;

    public ToolException(ErrorKind kind, String message) {
        this(kind, message, 0);
    }

    public ToolException(ErrorKind kind, String message, long retryAfterMs) {
        super(message);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
    }

    public ErrorKind kind() {
        return kind;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    public static ToolException timeout(long ms) {
        return new ToolException(ErrorKind.TIMEOUT, "工具调用超过 " + ms + "ms 未返回");
    }

    public static ToolException rateLimited(long retryAfterMs) {
        return new ToolException(ErrorKind.RATE_LIMIT, "上游限流 429", retryAfterMs);
    }

    public static ToolException upstream(String detail) {
        return new ToolException(ErrorKind.UPSTREAM_5XX, "上游 5xx: " + detail);
    }

    public static ToolException business(String detail) {
        return new ToolException(ErrorKind.BUSINESS, detail);
    }

    public static ToolException badArgument(String detail) {
        return new ToolException(ErrorKind.BAD_ARGUMENT, detail);
    }

    public static ToolException notFound(String detail) {
        return new ToolException(ErrorKind.NOT_FOUND, detail);
    }
}
