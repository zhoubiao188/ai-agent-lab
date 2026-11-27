package com.example.multiagent.api;

import com.example.multiagent.bench.CoordBench;
import com.example.multiagent.tasks.TaskCatalog;
import com.example.multiagent.tasks.TaskSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务集与策略清单,前端下拉框直接用。 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final TaskCatalog catalog;
    private final CoordBench bench;

    public CatalogController(TaskCatalog catalog, CoordBench bench) {
        this.catalog = catalog;
        this.bench = bench;
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return catalog.all().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", t.id());
            m.put("request", t.request());
            m.put("slots", t.slots());
            m.put("orderId", t.orderId());
            m.put("flaw", t.hasFlaw() ? t.forcedFlaw().name() : "CLEAN");
            return m;
        }).toList();
    }

    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return bench.policies().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", p.name());
            m.put("validate", p.validate());
            m.put("coverage", p.coverage());
            m.put("patch", p.patch());
            m.put("critic", p.critic());
            m.put("replan", p.replan());
            return m;
        }).toList();
    }

    /** 单任务的金标准,页面用来对照"计划覆盖了几个诉求" */
    @GetMapping("/tasks/{id}")
    public TaskSpec task(@org.springframework.web.bind.annotation.PathVariable String id) {
        return catalog.require(id);
    }
}
