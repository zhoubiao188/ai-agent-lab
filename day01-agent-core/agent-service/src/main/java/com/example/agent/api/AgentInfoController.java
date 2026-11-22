package com.example.agent.api;

import com.example.agent.agent.AgentOrchestrator;
import com.example.agent.agent.ConversationMemory;
import com.example.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 暴露服务自描述信息,前端用它决定是否提示"离线演示模式"。 */
@RestController
@RequestMapping("/api/agent")
public class AgentInfoController {

    private final AgentOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory memory;

    public AgentInfoController(AgentOrchestrator orchestrator, ToolRegistry toolRegistry, ConversationMemory memory) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        AgentOrchestrator.ProviderInfo info = orchestrator.info();
        return Map.of(
                "provider", info.provider(),
                "realModel", info.realModel(),
                "model", info.model(),
                "maxSteps", info.maxSteps(),
                "tools", info.tools(),
                "activeSessions", memory.sessionCount());
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return toolRegistry.specs().stream()
                .map(s -> Map.<String, Object>of(
                        "name", s.name(),
                        "description", s.description(),
                        "parameters", s.parameters()))
                .toList();
    }
}
