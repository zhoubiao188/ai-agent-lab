package com.example.multiagent.support;

import com.example.multiagent.bench.CoordBench;
import com.example.multiagent.coverage.IntentCoverage;
import com.example.multiagent.critic.Critic;
import com.example.multiagent.orch.Coordinator;
import com.example.multiagent.orch.GoldenCheck;
import com.example.multiagent.plan.Plan;
import com.example.multiagent.plan.PlanValidator;
import com.example.multiagent.planner.PlanFlawInjector;
import com.example.multiagent.planner.RulePlanner;
import com.example.multiagent.spec.ToolRegistry;
import com.example.multiagent.tasks.TaskCatalog;
import com.example.multiagent.tasks.TaskSpec;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 测试基类。延迟压到 2%,否则一个验证四条链路的用例要跑一秒多;
 * 工具故障关掉,因为这一篇要盯的是"计划错",两种错误混在一起就说不清了。
 */
@SpringBootTest(properties = {
        "tool.latency-scale=0.02",
        "tool.fault.enabled=false",
        "coord.budget-ms=15000"
})
public abstract class BaseTest {

    protected static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired
    protected ToolRegistry registry;
    @Autowired
    protected TaskCatalog catalog;
    @Autowired
    protected RulePlanner planner;
    @Autowired
    protected PlanFlawInjector flawInjector;
    @Autowired
    protected PlanValidator validator;
    @Autowired
    protected IntentCoverage coverage;
    @Autowired
    protected Critic critic;
    @Autowired
    protected GoldenCheck golden;
    @Autowired
    protected Coordinator coordinator;
    @Autowired
    protected CoordBench bench;

    protected TaskSpec task(String id) {
        return catalog.require(id);
    }

    /** 干净计划 */
    protected Plan clean(TaskSpec task) {
        return planner.plan(task);
    }

    protected ObjectNode args(Object... kv) {
        ObjectNode node = JSON.objectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v instanceof Integer n) {
                node.put(key, n);
            } else if (v instanceof Double d) {
                node.put(key, d);
            } else if (v instanceof Boolean b) {
                node.put(key, b);
            } else {
                node.put(key, String.valueOf(v));
            }
        }
        return node;
    }
}
