package com.example.memory.api.dto;

import com.example.memory.domain.Conversation;
import com.example.memory.memory.TokenEstimator;

/**
 * 会话列表项。刻意不返回完整消息 —— 侧边栏只需要标题、时间和"记忆水位",
 * 列表接口返回全量消息是典型的性能陷阱(20 个会话 × 2 万 token 的 JSON)。
 */
public record ConversationSummaryDto(
        String id,
        String title,
        long updatedAt,
        int messageCount,
        int tokensUsed,
        int budgetTokens,
        int compactRuns,
        int archivedMessages,
        String summaryPreview
) {

    public static ConversationSummaryDto of(Conversation c, int budgetTokens) {
        String summary = c.getSummary();
        String preview = summary == null || summary.isBlank() ? ""
                : summary.replaceAll("\\s+", " ").trim();
        if (preview.length() > 120) {
            preview = preview.substring(0, 120) + "…";
        }
        return new ConversationSummaryDto(
                c.getId(),
                c.getTitle() == null || c.getTitle().isBlank() ? "(未命名会话)" : c.getTitle(),
                c.getUpdatedAt(),
                c.getStats().getTotalMessages(),
                TokenEstimator.estimateMemory(c),
                budgetTokens,
                c.getStats().getCompactRuns(),
                (int) c.getStats().getArchivedMessages(),
                preview);
    }
}
