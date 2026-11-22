package com.example.agent.tool;

import com.example.agent.llm.dto.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表:启动时收集容器里所有 {@link AgentTool},构建名字索引与给模型用的 spec 列表。
 * 新增工具只需实现接口 + 加 @Component,零改动接入。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> discovered) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        discovered.stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .forEach(t -> {
                    if (map.putIfAbsent(t.name(), t) != null) {
                        throw new IllegalStateException("工具名重复: " + t.name());
                    }
                });
        this.tools = Map.copyOf(map);
        log.info("已注册 {} 个 Agent 工具: {}", tools.size(), tools.keySet());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.parameterSchema()))
                .toList();
    }

    public int size() {
        return tools.size();
    }
}
