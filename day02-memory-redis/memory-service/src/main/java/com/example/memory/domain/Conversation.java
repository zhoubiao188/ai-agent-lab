package com.example.memory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一个会话的<b>完整可持久化状态</b>。
 *
 * <p>设计上刻意把"活跃窗口"和"长期记忆"放在同一个聚合根里:</p>
 * <pre>
 * Conversation
 *   ├─ messages[]        活跃窗口(真正会发给模型的部分,会被压缩裁剪)
 *   ├─ summary           滚动摘要(被裁掉的历史,压缩成 500 字以内)
 *   ├─ summaryCoveredSeq 摘要覆盖到哪个 seq —— 幂等压缩的依据
 *   ├─ pinnedFacts[]     摘要容易漂移的关键标识符(单号/金额/日期),规则提取,永不丢失
 *   └─ stats             累计消息数、压缩次数、估算省下的 token
 * </pre>
 *
 * <p>归档消息单独存在 {@code agent:conv:{id}:archive} 里,不放在这里 ——
 * 因为每次对话都要整体读写这个对象,把几百条历史塞进来会让单次 Redis 往返从 2KB 涨到 100KB。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Conversation {

    private String id;
    private String userId;
    private String title;
    private long createdAt;
    private long updatedAt;

    /** 乐观锁版本号,Redis 侧用 Lua 做 CAS,防止多实例并发写覆盖 */
    private long version;

    /** 下一个可用序号。单调递增,只在 append 时增长 */
    private long nextSeq = 1;

    private List<Msg> messages = new ArrayList<>();

    /** 滚动摘要正文;空串表示还没压缩过 */
    private String summary = "";

    /** 摘要已覆盖的消息序号(含)。seq <= 该值的消息都已折叠进 summary */
    private long summaryCoveredSeq;

    /** 规则提取的关键标识符,压缩时永远保留,用于对抗摘要漂移 */
    private List<String> pinnedFacts = new ArrayList<>();

    private Stats stats = new Stats();

    public static class Stats {
        private int totalMessages;
        private int compactRuns;
        private long archivedMessages;
        private long tokensSavedEstimate;
        private int summarizerFailures;

        public int getTotalMessages() {
            return totalMessages;
        }

        public void setTotalMessages(int totalMessages) {
            this.totalMessages = totalMessages;
        }

        public int getCompactRuns() {
            return compactRuns;
        }

        public void setCompactRuns(int compactRuns) {
            this.compactRuns = compactRuns;
        }

        public long getArchivedMessages() {
            return archivedMessages;
        }

        public void setArchivedMessages(long archivedMessages) {
            this.archivedMessages = archivedMessages;
        }

        public long getTokensSavedEstimate() {
            return tokensSavedEstimate;
        }

        public void setTokensSavedEstimate(long tokensSavedEstimate) {
            this.tokensSavedEstimate = tokensSavedEstimate;
        }

        public int getSummarizerFailures() {
            return summarizerFailures;
        }

        public void setSummarizerFailures(int summarizerFailures) {
            this.summarizerFailures = summarizerFailures;
        }
    }

    public static Conversation create(String id, String userId) {
        Conversation c = new Conversation();
        c.id = id;
        c.userId = userId == null ? "anonymous" : userId;
        c.createdAt = System.currentTimeMillis();
        c.updatedAt = c.createdAt;
        c.title = "";
        return c;
    }

    // ---------------------------------------------------------------- 行为

    /** 追加一条消息,自动分配 seq 并刷新时间戳。 */
    public Msg append(String role, String content, String toolCallId, String name, List<ToolCall> toolCalls) {
        Msg m = new Msg(nextSeq++, role, content, toolCallId, name, toolCalls, System.currentTimeMillis());
        messages.add(m);
        stats.totalMessages++;
        touch();
        return m;
    }

    public Msg appendUser(String text) {
        Msg m = append(Msg.ROLE_USER, text, null, null, null);
        if (title == null || title.isBlank()) {
            title = text.length() > 40 ? text.substring(0, 40) + "…" : text;
        }
        return m;
    }

    public Msg appendAssistant(String content, List<ToolCall> toolCalls) {
        return append(Msg.ROLE_ASSISTANT, content, null, null, toolCalls);
    }

    public Msg appendTool(String toolCallId, String name, String content) {
        return append(Msg.ROLE_TOOL, content, toolCallId, name, null);
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
        this.version++;
    }

    /** 删除 seq <= coveredSeq 的消息(它们已经被摘要吸收)。 */
    public int dropCovered() {
        int before = messages.size();
        messages.removeIf(m -> m.seq() <= summaryCoveredSeq);
        return before - messages.size();
    }

    @JsonIgnore
    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }

    // ---------------------------------------------------------------- getter / setter

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public long getNextSeq() {
        return nextSeq;
    }

    public void setNextSeq(long nextSeq) {
        this.nextSeq = Math.max(1, nextSeq);
    }

    public List<Msg> getMessages() {
        return messages;
    }

    public void setMessages(List<Msg> messages) {
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
    }

    public String getSummary() {
        return summary == null ? "" : summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public long getSummaryCoveredSeq() {
        return summaryCoveredSeq;
    }

    public void setSummaryCoveredSeq(long summaryCoveredSeq) {
        this.summaryCoveredSeq = summaryCoveredSeq;
    }

    public List<String> getPinnedFacts() {
        return pinnedFacts;
    }

    public void setPinnedFacts(List<String> pinnedFacts) {
        this.pinnedFacts = pinnedFacts == null ? new ArrayList<>() : new ArrayList<>(pinnedFacts);
    }

    public Stats getStats() {
        return stats == null ? new Stats() : stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats == null ? new Stats() : stats;
    }

    /** 去重合并关键事实,保留最近 24 条(越新的越靠后,超限丢最旧的)。 */
    public void mergePinnedFacts(List<String> facts) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(pinnedFacts);
        for (String f : facts) {
            if (f == null || f.isBlank()) {
                continue;
            }
            merged.remove(f);
            merged.add(f);
        }
        List<String> list = new ArrayList<>(merged);
        if (list.size() > 24) {
            list = list.subList(list.size() - 24, list.size());
        }
        this.pinnedFacts = list;
    }
}
