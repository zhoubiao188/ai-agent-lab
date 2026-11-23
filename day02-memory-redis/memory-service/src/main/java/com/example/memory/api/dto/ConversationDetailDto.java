package com.example.memory.api.dto;

import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.example.memory.memory.TokenEstimator;

import java.util.List;

/**
 * 会话详情。前端右侧"记忆面板"全靠它:
 * 摘要原文、关键事实、token 水位、归档条数、累计省下的 token。
 */
public record ConversationDetailDto(
        String id,
        String userId,
        String title,
        long createdAt,
        long updatedAt,
        String summary,
        long summaryCoveredSeq,
        List<String> pinnedFacts,
        List<Msg> messages,
        Conversation.Stats stats,
        int tokensUsed,
        int summaryTokens,
        int historyTokens,
        int budgetTokens,
        int archivedCount
) {

    public static ConversationDetailDto of(Conversation c, int budgetTokens, int archivedCount) {
        return new ConversationDetailDto(
                c.getId(),
                c.getUserId(),
                c.getTitle(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getSummary(),
                c.getSummaryCoveredSeq(),
                c.getPinnedFacts(),
                c.getMessages(),
                c.getStats(),
                TokenEstimator.estimateMemory(c),
                TokenEstimator.estimate(c.getSummary()),
                TokenEstimator.estimate(c.getMessages()),
                budgetTokens,
                archivedCount);
    }
}
