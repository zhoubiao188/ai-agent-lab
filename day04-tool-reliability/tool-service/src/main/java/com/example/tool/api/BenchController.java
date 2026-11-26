package com.example.tool.api;

import com.example.tool.bench.BenchReport;
import com.example.tool.bench.BenchRunner;
import com.example.tool.config.ToolProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照实验接口。同步执行,因为前端要的就是"跑完给一张表"。
 * 生产上这种活该丢到任务队列,这里为了演示链路清晰保持同步。
 */
@RestController
@RequestMapping("/api")
public class BenchController {

    private final BenchRunner runner;
    private final ToolProperties props;

    public BenchController(BenchRunner runner, ToolProperties props) {
        this.runner = runner;
        this.props = props;
    }

    public record BenchRequest(Integer tasks, Long seed, Double scale) {
    }

    @PostMapping("/bench/run")
    public BenchReport run(@RequestBody(required = false) BenchRequest req) {
        int tasks = req == null || req.tasks() == null ? props.bench().tasks() : req.tasks();
        long seed = req == null || req.seed() == null ? props.bench().seed() : req.seed();
        double scale = req == null || req.scale() == null ? 0.15 : req.scale();
        tasks = Math.max(5, Math.min(tasks, 300));
        scale = Math.max(0.02, Math.min(scale, 1.0));
        return runner.run(tasks, seed, scale);
    }
}
