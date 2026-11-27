package com.example.multiagent.exec;

import com.example.multiagent.config.ToolProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器与指标的容器。按工具名懒加载,生命周期跟进程一致。
 * 评测要跑多组配置,所以提供 {@link #reset()} 把状态清干净 —— 不清就会串味,
 * 上一组跑出来的熔断状态会让下一组的成功率莫名其妙地变好看。
 */
@Component
public class ResilienceRegistry {

    private final ToolProperties props;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, ToolMetrics> metrics = new ConcurrentHashMap<>();

    public ResilienceRegistry(ToolProperties props) {
        this.props = props;
    }

    public CircuitBreaker breaker(String tool) {
        return breakers.computeIfAbsent(tool, name -> {
            ToolProperties.Breaker cfg = props.breaker();
            return new CircuitBreaker(name, cfg.windowMs(), cfg.minCalls(), cfg.failureRate(),
                    cfg.openMs(), cfg.halfOpenProbes());
        });
    }

    public ToolMetrics metrics(String tool) {
        return metrics.computeIfAbsent(tool, name -> new ToolMetrics());
    }

    public Map<String, ToolMetrics.Snapshot> snapshot() {
        Map<String, ToolMetrics.Snapshot> out = new LinkedHashMap<>();
        metrics.keySet().stream().sorted().forEach(k -> out.put(k, metrics.get(k).snapshot()));
        return out;
    }

    public Map<String, CircuitBreaker.Snapshot> breakerSnapshot() {
        Map<String, CircuitBreaker.Snapshot> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(k -> out.put(k, breakers.get(k).snapshot()));
        return out;
    }

    public void reset() {
        breakers.values().forEach(CircuitBreaker::reset);
        metrics.values().forEach(ToolMetrics::reset);
    }
}
