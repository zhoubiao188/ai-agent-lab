package com.example.agent.api.dto;

import java.util.List;

/** 元信息:20 个决策点、6 个剧本、示例任务。前端不做硬编码,全都从接口取。 */
public final class MetaDtos {

    private MetaDtos() {
    }

    public record DecisionPointDto(String code, String title, String question, String symptom) {
    }

    public record ScenarioDto(String name, String label, String description, String culpritTool) {
    }

    public record TaskDto(String id, String question, String scenario, String why) {
    }

    public record CatalogDto(List<DecisionPointDto> decisions, List<ScenarioDto> scenarios,
                             List<TaskDto> tasks, int toolCount) {
    }

    public record ToolStatDto(String tool, int calls, int errors, double avgAttempts) {
    }

    public record OverviewDto(int runs, double successRate, double silentRate, double p50Ms, double p95Ms,
                              double avgTokens, double costPerSuccessMicros, double avgRetryAmp,
                              double avgSpans, double avgDecisions, List<ToolStatDto> tools) {
    }

    public record RunResponse(String traceId, String answer, String outcome, double trust,
                              double durationMs, long tokens, List<SuspectDto> suspects,
                              String narrative) {
    }
}
