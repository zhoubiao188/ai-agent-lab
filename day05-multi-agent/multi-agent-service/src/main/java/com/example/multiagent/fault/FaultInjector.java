package com.example.multiagent.fault;

import com.example.multiagent.config.ToolProperties;
import com.example.multiagent.exec.ErrorKind;
import com.example.multiagent.exec.ToolException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 故障注入器。
 *
 * <p>没有故障注入的"可靠性"都是玄学:你写的重试逻辑可能从来没被触发过,
 * 熔断器可能永远不会打开,而单元测试里全是 happy path。</p>
 *
 * <p>两条设计原则:</p>
 * <p>1、<b>确定性</b>。同样的 seed + 工具名 + 调用 ID + 尝试次数,一定得到同样的结论。
 * 这样 L0~L3 四组配置面对的是完全相同的故障序列,成功率才有可比性。</p>
 * <p>2、<b>注入点在工具执行之前</b>。重试之所以有用,是因为第二次尝试有可能成功——
 * 故障必须跟着 attempt 变化,而不是一个调用从头错到尾。</p>
 */
@Component
public class FaultInjector {

    /** 强制规则:某个工具永远失败,用来测熔断 */
    public record ForcedFault(String tool, ErrorKind kind, String detail) {
    }

    private final ToolProperties props;
    private final List<ForcedFault> forced = new CopyOnWriteArrayList<>();
    private volatile long seed;
    private volatile boolean enabled;

    public FaultInjector(ToolProperties props) {
        this.props = props;
        this.seed = props.fault().seed();
        this.enabled = props.fault().enabled();
    }

    public void configure(long seed, boolean enabled) {
        this.seed = seed;
        this.enabled = enabled;
    }

    public void force(ForcedFault fault) {
        forced.add(fault);
    }

    public void clearForced() {
        forced.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public void maybeFail(String tool, String callId, int attempt) {
        for (ForcedFault f : forced) {
            if (f.tool().equals(tool)) {
                throw toException(f.kind(), f.detail());
            }
        }
        if (!enabled) {
            return;
        }
        ToolProperties.Fault cfg = props.fault();
        Random rnd = draw(tool, callId, attempt, 1L);
        double d = rnd.nextDouble();
        if (d < cfg.timeoutRate()) {
            throw ToolException.timeout(props.timeoutMs());
        }
        d -= cfg.timeoutRate();
        if (d < cfg.rateLimitRate()) {
            throw ToolException.rateLimited(20 + rnd.nextInt(40));
        }
        d -= cfg.rateLimitRate();
        if (d < cfg.upstreamRate()) {
            throw ToolException.upstream("remote service unavailable");
        }
        d -= cfg.upstreamRate();
        if (d < cfg.businessRate()) {
            throw ToolException.business("下游业务规则拒绝:当前状态不允许该操作");
        }
    }

    /** 参数污染:返回 null 表示这个调用不污染 */
    public CorruptionKind corruptionFor(String callId) {
        if (!enabled) {
            return null;
        }
        Random rnd = draw("arg", callId, 0, 2L);
        if (rnd.nextDouble() >= props.fault().corruptRate()) {
            return null;
        }
        CorruptionKind[] kinds = CorruptionKind.values();
        return kinds[rnd.nextInt(kinds.length)];
    }

    private ToolException toException(ErrorKind kind, String detail) {
        return switch (kind) {
            case TIMEOUT -> ToolException.timeout(props.timeoutMs());
            case RATE_LIMIT -> ToolException.rateLimited(30);
            case UPSTREAM_5XX -> ToolException.upstream(detail == null ? "forced" : detail);
            case BUSINESS -> ToolException.business(detail == null ? "forced business error" : detail);
            case NOT_FOUND -> ToolException.notFound(detail == null ? "forced not found" : detail);
            default -> new ToolException(kind, detail == null ? "forced" : detail);
        };
    }

    /**
     * 确定性地构造随机源:同一组输入永远得到同一个序列。
     * 用 0x9E3779B97F4A7C15(黄金比例常数)做混合,避免低位重复。
     */
    private Random draw(String tool, String callId, int attempt, long salt) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + tool.hashCode();
        h = h * 0x9E3779B97F4A7C15L + callId.hashCode();
        h = h * 0x9E3779B97F4A7C15L + attempt;
        h = h * 0x9E3779B97F4A7C15L + salt;
        return new Random(h);
    }
}
