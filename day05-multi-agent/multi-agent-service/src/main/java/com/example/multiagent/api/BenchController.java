package com.example.multiagent.api;

import com.example.multiagent.bench.BenchReport;
import com.example.multiagent.bench.CoordBench;
import com.example.multiagent.tasks.TaskCatalog;
import com.example.multiagent.tasks.TaskSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 一键跑四组对照。默认跑全量 20 个任务,{@code limit} 只是为了让前端调参时别等太久。 */
@RestController
@RequestMapping("/api")
public class BenchController {

    private final CoordBench bench;
    private final TaskCatalog catalog;

    public BenchController(CoordBench bench, TaskCatalog catalog) {
        this.bench = bench;
        this.catalog = catalog;
    }

    @GetMapping("/bench")
    public BenchReport bench(@RequestParam(defaultValue = "0") int limit) {
        List<TaskSpec> tasks = catalog.all();
        if (limit > 0 && limit < tasks.size()) {
            tasks = tasks.subList(0, limit);
        }
        return bench.run(bench.policies(), tasks);
    }
}
