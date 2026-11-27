package com.example.multiagent.spec;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表。启动时一次性注册,运行期只读 —— 工具集变动要走重启或显式的 refresh 接口,
 * 不做热插拔。理由很朴素:热更新工具定义会让"同一次任务里两个调用拿到不同 schema"成为可能,
 * 排查起来是噩梦。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolSpec> specs = new ConcurrentHashMap<>();
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

    public void register(ToolSpec spec, ToolHandler handler) {
        specs.put(spec.name(), spec);
        handlers.put(spec.name(), handler);
    }

    public Optional<ToolSpec> spec(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    public ToolSpec requireSpec(String name) {
        ToolSpec spec = specs.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return spec;
    }

    public ToolHandler requireHandler(String name) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler;
    }

    public List<ToolSpec> all() {
        Map<String, ToolSpec> sorted = new LinkedHashMap<>();
        specs.keySet().stream().sorted().forEach(k -> sorted.put(k, specs.get(k)));
        return List.copyOf(sorted.values());
    }

    public int size() {
        return specs.size();
    }
}
