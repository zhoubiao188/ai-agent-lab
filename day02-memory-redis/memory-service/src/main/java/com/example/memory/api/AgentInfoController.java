package com.example.memory.api;

import com.example.memory.agent.AgentOrchestrator;
import com.example.memory.config.AgentProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 自述接口:前端启动时拉一次,用于展示"当前是不是离线演示模式、记忆存在哪"。 */
@RestController
@RequestMapping("/api/agent")
public class AgentInfoController {

    private final AgentOrchestrator orchestrator;
    private final AgentProperties props;

    public AgentInfoController(AgentOrchestrator orchestrator, AgentProperties props) {
        this.orchestrator = orchestrator;
        this.props = props;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", orchestrator.info().provider());
        m.put("realModel", orchestrator.info().realModel());
        m.put("model", orchestrator.info().model());
        m.put("maxSteps", orchestrator.info().maxSteps());
        m.put("tools", orchestrator.info().tools());
        m.put("store", orchestrator.info().store());
        m.put("memory", Map.of(
                "budgetTokens", props.getMemory().getBudgetTokens(),
                "compactThreshold", props.getMemory().getCompactThreshold(),
                "keepTailTokens", props.getMemory().getKeepTailTokens(),
                "recallTopK", props.getMemory().getRecallTopK()));
        return m;
    }
}
