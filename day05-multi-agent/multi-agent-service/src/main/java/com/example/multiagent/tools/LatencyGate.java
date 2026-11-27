package com.example.multiagent.tools;

import com.example.multiagent.config.ToolProperties;
import org.springframework.stereotype.Component;

/**
 * 延迟闸门。存在的唯一理由是评测要跑得快:
 * 120 个任务 × 4 组配置 × 6 个调用,按真实延迟要跑二十多分钟,
 * 压到 15% 之后两分钟内能出结果,而成功率完全不受影响。
 *
 * <p>它不该出现在生产代码里 —— 生产要的是真实延迟。这里保留是因为
 * "能反复重跑的实验"比"看起来漂亮的数字"更重要。</p>
 */
@Component
public class LatencyGate {

    private final double base;
    private volatile double scale;

    public LatencyGate(ToolProperties props) {
        this.base = props.latencyScale();
        this.scale = props.latencyScale();
    }

    public long apply(long baseMs) {
        return Math.max(1, (long) (baseMs * scale));
    }

    public void scale(double newScale) {
        this.scale = newScale;
    }

    public void reset() {
        this.scale = base;
    }

    public double current() {
        return scale;
    }
}
