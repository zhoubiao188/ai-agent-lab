package com.example.memory.api;

import com.example.memory.agent.ConversationService;
import com.example.memory.api.dto.ConversationDetailDto;
import com.example.memory.api.dto.ConversationSummaryDto;
import com.example.memory.api.dto.MemoryStatsDto;
import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.memory.MemoryRecaller;
import com.example.memory.memory.SummaryCompactor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与记忆管理接口。
 *
 * <p>这些接口不只是"给前端用的",更是排查线上问题的抓手:</p>
 * <ul>
 *   <li>{@code GET /api/conversations/{id}} 能看到摘要原文和关键事实 —— 用户说"你忘了",
 *       直接看这个就知道是没记、记错了、还是召不回;</li>
 *   <li>{@code POST /api/conversations/{id}/compact} 手动触发压缩 —— 复现"压缩导致 400"的利器;</li>
 *   <li>{@code GET /api/conversations/{id}/archive?q=} 直接看召回打分 —— 调阈值不用猜。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService conversations;
    private final AgentProperties props;

    public ConversationController(ConversationService conversations, AgentProperties props) {
        this.conversations = conversations;
        this.props = props;
    }

    @GetMapping("/conversations")
    public List<ConversationSummaryDto> list(@RequestParam(defaultValue = "anonymous") String userId,
                                             @RequestParam(defaultValue = "20") int limit) {
        int budget = props.getMemory().getBudgetTokens();
        return conversations.recent(userId, Math.min(Math.max(limit, 1), 100)).stream()
                .map(c -> ConversationSummaryDto.of(c, budget))
                .toList();
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetailDto> detail(@PathVariable String id) {
        return conversations.find(id)
                .map(c -> ResponseEntity.ok(ConversationDetailDto.of(c,
                        props.getMemory().getBudgetTokens(), conversations.archiveSize(id))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        conversations.delete(id);
        return Map.of("conversationId", id, "deleted", true);
    }

    /** 手动压缩:force=true 时忽略水位线,用于演示与复现问题 */
    @PostMapping("/conversations/{id}/compact")
    public Map<String, Object> compact(@PathVariable String id,
                                       @RequestParam(defaultValue = "true") boolean force) {
        SummaryCompactor.Result r = conversations.compact(id, force);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversationId", id);
        m.put("compacted", r.compacted());
        m.put("degraded", r.degraded());
        m.put("mode", r.mode());
        m.put("beforeTokens", r.beforeTokens());
        m.put("afterTokens", r.afterTokens());
        m.put("foldedMessages", r.foldedMessages());
        m.put("coveredSeq", r.coveredSeq());
        m.put("summary", r.summary());
        return m;
    }

    /** 归档检索:不传 q 时返回最近归档原文;传 q 时返回带打分的召回结果 */
    @GetMapping("/conversations/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversationId", id);
        m.put("size", conversations.archiveSize(id));
        if (q != null && !q.isBlank()) {
            List<MemoryRecaller.Hit> hits = conversations.recall(id, q);
            m.put("query", q);
            m.put("hits", hits);
        } else {
            List<Msg> msgs = conversations.store().archive(id, limit);
            m.put("messages", msgs);
        }
        return m;
    }

    @GetMapping("/memory/stats")
    public MemoryStatsDto stats() {
        return new MemoryStatsDto(
                conversations.store().type(),
                conversations.store().conversationCount(),
                conversations.activeLocks(),
                conversations.summarizerBreakerOpen(),
                props.getMemory().getBudgetTokens(),
                props.getMemory().getTtl().toSeconds());
    }

    @ExceptionHandler(ConversationService.BusyException.class)
    public ResponseEntity<Map<String, Object>> onBusy(ConversationService.BusyException e) {
        return ResponseEntity.status(429).body(Map.of(
                "error", "conversation_busy",
                "message", e.getMessage()));
    }

    @ExceptionHandler(ConversationService.ConcurrentUpdateException.class)
    public ResponseEntity<Map<String, Object>> onConflict(ConversationService.ConcurrentUpdateException e) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "conversation_conflict",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "bad_request",
                "message", e.getMessage()));
    }
}
