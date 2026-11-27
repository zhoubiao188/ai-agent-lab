package com.example.multiagent.exec;

import com.example.multiagent.config.ToolProperties;
import com.example.multiagent.spec.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 重试决策。
 *
 * <p>四条铁律,顺序也是它们在代码里的顺序:</p>
 * <p>1、只重试可重试的错误类型。把 404 重试三次,只是让"查不到"变成"查不到且更慢"。</p>
 * <p>2、非幂等写操作不重试。除非工具声明了 idempotent 并且调用带了幂等键。</p>
 * <p>3、退避带抖动。三个工具同时重试、且步长一致,就等于自己造了一次小型 DDoS。</p>
 * <p>4、Retry-After 大于退避上限时,放弃比提前重试更划算。上游明说 30 秒后再来,
 * 你 400ms 后就去敲,拿到的还是 429,只是多烧了一次配额。</p>
 */
@Component
public class RetryPolicy {

    private final ToolProperties props;
    private final Random random = new Random(20261126L);

    public RetryPolicy(ToolProperties props) {
        this.props = props;
    }

    public record Decision(boolean retry, long delayMs, String reason) {
        public static Decision stop(String reason) {
            return new Decision(false, 0, reason);
        }
    }

    /**
     * @param attempt 已经失败的次数(第一次失败后传 1)
     */
    public Decision decide(int attempt, ToolException e, ToolSpec spec) {
        if (attempt >= props.maxAttempts()) {
            return Decision.stop("已达最大尝试次数 " + props.maxAttempts());
        }
        ErrorKind kind = e.kind();
        if (!kind.retryable()) {
            return Decision.stop(kind + " 不属于可重试错误");
        }
        if (kind == ErrorKind.TIMEOUT && !props.retryOnTimeout()) {
            return Decision.stop("超时重试已关闭");
        }
        if (kind == ErrorKind.RATE_LIMIT && !props.retryOnRateLimit()) {
            return Decision.stop("限流重试已关闭");
        }
        if (kind == ErrorKind.UPSTREAM_5XX && !props.retryOnUpstream5xx()) {
            return Decision.stop("5xx 重试已关闭");
        }
        if (spec.writeOperation() && !(spec.idempotent() && props.retryWrites())) {
            return Decision.stop("写操作非幂等,禁止自动重试");
        }
        long delay = backoff(attempt);
        if (kind == ErrorKind.RATE_LIMIT && e.retryAfterMs() > 0) {
            if (e.retryAfterMs() > props.backoffMaxMs()) {
                return Decision.stop("Retry-After " + e.retryAfterMs() + "ms 超过退避上限,提前重试无意义");
            }
            delay = Math.max(delay, e.retryAfterMs());
        }
        return new Decision(true, delay, kind + " 第 " + attempt + " 次失败,退避 " + delay + "ms 后重试");
    }

    /** 指数退避 + 向下抖动:第 1 次 base,第 2 次 2×base …… 上限 backoffMax */
    long backoff(int attempt) {
        long exp = props.backoffBaseMs() * (1L << Math.min(Math.max(attempt - 1, 0), 10));
        long capped = Math.min(exp, props.backoffMaxMs());
        double factor = 1.0 - props.jitter() * random.nextDouble();
        return Math.max(1, (long) (capped * factor));
    }
}
