package com.example.tool.api;

import com.example.tool.exec.ExecutionContext;
import com.example.tool.exec.RunPolicy;
import com.example.tool.exec.ToolCall;
import com.example.tool.exec.ToolInvoker;
import com.example.tool.exec.ToolResult;
import com.example.tool.spec.ToolSpec;
import com.example.tool.spec.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具清单与单工具直调。直调接口的价值在于排障:
 * 线上"退款失败"时,先绕过模型直接打一次工具,能立刻分清是模型参数错还是工具本身坏。
 */
@RestController
@RequestMapping("/api")
public class ToolsController {

    private final ToolRegistry registry;
    private final ToolInvoker invoker;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolsController(ToolRegistry registry, ToolInvoker invoker) {
        this.registry = registry;
        this.invoker = invoker;
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec spec : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", spec.name());
            m.put("description", spec.description());
            m.put("write", spec.writeOperation());
            m.put("idempotent", spec.idempotent());
            m.put("schema", spec.schema());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/tools/{name}/invoke")
    public Map<String, Object> invoke(@PathVariable String name,
                                     @RequestBody(required = false) JsonNode body) {
        ObjectNode args = body == null || !body.isObject()
                ? mapper.createObjectNode()
                : (ObjectNode) body;
        long budget = 30_000;
        ExecutionContext ctx = ExecutionContext.of("direct-" + System.nanoTime(), budget);
        ToolResult result = invoker.invoke(ToolCall.of("direct", name, args), ctx, RunPolicy.FULL);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", name);
        out.put("status", result.status());
        out.put("attempts", result.attempts());
        out.put("durationMs", result.durationMs());
        out.put("payload", result.payload());
        out.put("errorKind", result.errorKind());
        out.put("error", result.error());
        out.put("repairs", result.repairs());
        out.put("attemptLog", result.attemptLog());
        return out;
    }
}
