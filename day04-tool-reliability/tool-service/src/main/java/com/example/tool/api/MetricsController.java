package com.example.tool.api;

import com.example.tool.exec.ResilienceRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 指标与熔断状态。前端右上角那排数字就是它。
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final ResilienceRegistry resilience;

    public MetricsController(ResilienceRegistry resilience) {
        this.resilience = resilience;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "tools", resilience.snapshot(),
                "breakers", resilience.breakerSnapshot());
    }
}
