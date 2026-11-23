package com.example.memory.store;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内实现:单机开发、单元测试、以及 Redis 挂掉时的兜底都用它。
 *
 * <p>两个细节值得注意:</p>
 * <ul>
 *   <li><b>读写都做深拷贝</b>。如果直接把对象引用交出去,调用方改完没保存,内存里的"数据库"
 *       就已经被改脏了 —— 这类 bug 只在"保存失败"的路径上才暴露,极难排查。用 Jackson
 *       序列化一份副本,代价是几微秒,换来的是语义和 Redis 实现完全一致。</li>
 *   <li><b>CAS 用版本号 + synchronized</b>。单机也不该允许并发覆盖:两个线程同时处理同一会话的
 *       两条消息,后完成的那个会把前一个的写入抹掉。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "agent.memory.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryChatMemoryStore.class);

    private final Map<String, Entry> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<Msg>> archives = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final long ttlMillis;

    private static final class Entry {
        final Conversation conversation;
        volatile long lastAccess;

        Entry(Conversation conversation) {
            this.conversation = conversation;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    public InMemoryChatMemoryStore(ObjectMapper mapper, AgentProperties props) {
        this.mapper = mapper;
        this.ttlMillis = props.getMemory().getTtl().toMillis();
    }

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public Optional<Conversation> load(String conversationId) {
        Entry e = conversations.get(conversationId);
        if (e == null) {
            return Optional.empty();
        }
        if (expired(e)) {
            conversations.remove(conversationId);
            archives.remove(conversationId);
            return Optional.empty();
        }
        e.lastAccess = System.currentTimeMillis();
        synchronized (e) {
            return Optional.of(copy(e.conversation));
        }
    }

    @Override
    public boolean save(Conversation conversation, long expectedVersion) {
        Entry e = conversations.get(conversation.getId());
        if (e == null) {
            if (expectedVersion != 0) {
                return false;
            }
            Entry fresh = new Entry(Conversation.create(conversation.getId(), conversation.getUserId()));
            Entry prev = conversations.putIfAbsent(conversation.getId(), fresh);
            e = prev != null ? prev : fresh;
        }
        synchronized (e) {
            if (e.conversation.getVersion() != expectedVersion) {
                return false;
            }
            apply(e.conversation, copy(conversation));
            e.lastAccess = System.currentTimeMillis();
            return true;
        }
    }

    private void apply(Conversation target, Conversation copy) {
        target.setUserId(copy.getUserId());
        target.setTitle(copy.getTitle());
        target.setCreatedAt(copy.getCreatedAt());
        target.setUpdatedAt(copy.getUpdatedAt());
        target.setVersion(copy.getVersion());
        target.setNextSeq(copy.getNextSeq());
        target.setMessages(copy.getMessages());
        target.setSummary(copy.getSummary());
        target.setSummaryCoveredSeq(copy.getSummaryCoveredSeq());
        target.setPinnedFacts(copy.getPinnedFacts());
        target.setStats(copy.getStats());
    }

    @Override
    public void delete(String conversationId) {
        conversations.remove(conversationId);
        archives.remove(conversationId);
    }

    @Override
    public List<String> listIds(String userId, int limit) {
        return conversations.values().stream()
                .filter(e -> !expired(e))
                .filter(e -> userId == null || userId.equals(e.conversation.getUserId()))
                .sorted(Comparator.comparingLong((Entry e) -> e.conversation.getUpdatedAt()).reversed())
                .limit(Math.max(1, limit))
                .map(e -> e.conversation.getId())
                .toList();
    }

    @Override
    public void appendArchive(String conversationId, List<Msg> messages, int maxMessages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Msg> list = archives.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (list) {
            list.addAll(messages);
            int overflow = list.size() - Math.max(1, maxMessages);
            if (overflow > 0) {
                list.subList(0, overflow).clear();
            }
        }
    }

    @Override
    public List<Msg> archive(String conversationId, int limit) {
        List<Msg> list = archives.get(conversationId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            int from = Math.max(0, list.size() - Math.max(1, limit));
            return List.copyOf(list.subList(from, list.size()));
        }
    }

    @Override
    public int archiveSize(String conversationId) {
        List<Msg> list = archives.get(conversationId);
        return list == null ? 0 : list.size();
    }

    @Override
    public long conversationCount() {
        return conversations.size();
    }

    /** 每分钟清一次过期会话,避免长跑进程内存泄漏(Redis 侧靠 TTL,不需要这个)。 */
    @Scheduled(fixedDelay = 60_000L)
    public void evictExpired() {
        int before = conversations.size();
        conversations.entrySet().removeIf(e -> {
            if (expired(e.getValue())) {
                archives.remove(e.getKey());
                return true;
            }
            return false;
        });
        if (before != conversations.size()) {
            log.debug("清理过期会话 {} 个,剩余 {}", before - conversations.size(), conversations.size());
        }
    }

    private boolean expired(Entry e) {
        return System.currentTimeMillis() - e.lastAccess > ttlMillis;
    }

    private Conversation copy(Conversation c) {
        return mapper.convertValue(c, Conversation.class);
    }

    /** 仅测试用:清空所有数据 */
    void reset() {
        conversations.clear();
        archives.clear();
    }

    Map<String, Object> debugSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversations", conversations.size());
        m.put("archives", archives.size());
        return m;
    }
}
