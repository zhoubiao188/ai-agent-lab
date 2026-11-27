package com.example.agent.api;

import com.example.agent.bench.BenchReport;
import com.example.agent.bench.TriageBench;
import com.example.agent.config.AgentProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测接口。同步执行,因为它在虚拟时钟下只要几秒 —— 不引入任务队列,
 * 读者可以直接 curl 一次拿到完整报告。
 */
@RestController
@RequestMapping("/api/bench")
public class BenchController {

    private final TriageBench bench;
    private final AgentProperties props;

    public BenchController(TriageBench bench, AgentProperties props) {
        this.bench = bench;
        this.props = props;
    }

    @PostMapping("/triage")
    public BenchReport triage(@RequestParam(required = false) Integer incidents,
                              @RequestParam(required = false) Long seed) {
        int count = incidents == null ? props.getBenchIncidents() : Math.min(Math.max(incidents, 5), 1000);
        long s = seed == null ? props.getBenchSeed() : seed;
        return bench.run(count, s);
    }
}
