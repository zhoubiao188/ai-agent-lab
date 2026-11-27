package com.example.agent.api;

import com.example.agent.api.dto.MetaDtos;
import com.example.agent.api.dto.RunRequest;
import com.example.agent.api.dto.TraceDetail;
import com.example.agent.fault.Scenario;
import com.example.agent.pipeline.AgentPipeline;
import com.example.agent.pipeline.RunResult;
import com.example.o11y.export.JsonlExporter;
import com.example.o11y.span.Trace;
import com.example.o11y.store.TraceStore;
import com.example.o11y.triage.TriageEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 运行与追踪查询接口。
 *
 * <p>响应头一定带 {@code X-Trace-Id}。这一点很关键:用户在页面上说"这次不对"时,
 * 你要能拿一个 id 就去后台捞,而不是问"你什么时候点的"。</p>
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final AgentPipeline pipeline;
    private final TraceStore store;
    private final TraceMapper mapper;
    private final TriageEngine triage;
    private final JsonlExporter exporter;

    public RunController(AgentPipeline pipeline, TraceStore store, TraceMapper mapper,
                         TriageEngine triage, JsonlExporter exporter) {
        this.pipeline = pipeline;
        this.store = store;
        this.mapper = mapper;
        this.triage = triage;
        this.exporter = exporter;
    }

    @PostMapping("/runs")
    public ResponseEntity<MetaDtos.RunResponse> run(@Valid @RequestBody RunRequest request) {
        Scenario scenario = Scenario.of(request.scenario());
        String userId = request.userId() == null || request.userId().isBlank() ? "u-demo" : request.userId();
        String taskId = "T-" + UUID.randomUUID().toString().substring(0, 8);

        RunResult result = pipeline.run(request.question(), scenario, userId, taskId);
        Trace trace = store.find(result.traceId()).orElseThrow();

        MetaDtos.RunResponse body = new MetaDtos.RunResponse(
                trace.traceId(), result.answer(), trace.outcome().name(), trace.trust(),
                Math.round(trace.totalMs() * 10) / 10.0, trace.totalTokens(),
                result.triage().suspects().stream().map(mapper::suspect).toList(),
                result.triage().narrative());

        return ResponseEntity.ok()
                .header("X-Trace-Id", trace.traceId())
                .body(body);
    }

    @GetMapping("/runs")
    public List<com.example.agent.api.dto.RunSummaryDto> list(
            @RequestParam(defaultValue = "20") int limit) {
        return store.recent(Math.min(Math.max(limit, 1), 200)).stream()
                .map(t -> mapper.summary(t, triage.triage(t, 3)))
                .toList();
    }

    @GetMapping("/runs/{traceId}")
    public TraceDetail detail(@PathVariable String traceId) {
        Trace trace = store.find(traceId)
                .orElseThrow(() -> new NotFoundException("trace 不存在:" + traceId));
        return mapper.detail(trace, triage.triage(trace, 3));
    }

    /** 一行一个 Span 的 JSONL,直接可 grep / jq / 导入 ClickHouse。 */
    @GetMapping(value = "/runs/{traceId}/export", produces = "application/x-ndjson")
    public ResponseEntity<String> export(@PathVariable String traceId) {
        Trace trace = store.find(traceId)
                .orElseThrow(() -> new NotFoundException("trace 不存在:" + traceId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + traceId + ".jsonl\"")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(exporter.export(trace));
    }
}
