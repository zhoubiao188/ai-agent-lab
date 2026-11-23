package com.example.memory.agent;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.memory.MemoryRecaller;
import com.example.memory.memory.SummaryCompactor;
import com.example.memory.memory.TokenEstimator;
import com.example.memory.store.ChatMemoryStore;
import com.example.memory.store.StripedLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话生命周期的唯一入口:加载 → 加锁 → 追加用户消息 → 压缩 → 召回 → 落盘。
 *
 * <p>把这一串放在 Service 而不是 Controller 或 Orchestrator 里,是为了让"记忆"成为
 * 一个可以独立测试、独立替换的横切关注点:换存储、换压缩策略、加召回,都不需要动 Agent 编排。</p>
 *
 * <p>关于落盘时机,这里做了个折中:<b>每次 LLM step 结束后都保存一次</b>。
 * 只在整轮结束时保存看起来更省 Redis 往返,但用户一旦关页面/断网,这一轮就凭空消失了,
 * 而 SSE 断连在移动端非常常见。多写一两次(每次 2~5KB)换来"断线也不丢",值得。</p>
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 同一会话的上一条消息还在跑 */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    /** 乐观锁冲突:其他实例/线程已经改过这个会话 */
    public static class ConcurrentUpdateException extends RuntimeException {
        public ConcurrentUpdateException(String message) {
            super(message);
        }
    }

    private final ChatMemoryStore store;
    private final SummaryCompactor compactor;
    private final MemoryRecaller recaller;
    private final StripedLocks locks;
    private final AgentProperties props;

    public ConversationService(ChatMemoryStore store,
                               SummaryCompactor compactor,
                               MemoryRecaller recaller,
                               StripedLocks locks,
                               AgentProperties props) {
        this.store = store;
        this.compactor = compactor;
        this.recaller = recaller;
        this.locks = locks;
        this.props = props;
    }

    /** 一轮对话持有的上下文。必须在 finally 里 close,否则锁不释放。 */
    public static final class TurnSession implements AutoCloseable {
        private final Conversation conversation;
        private final List<MemoryRecaller.Hit> recallHits;
        private final SummaryCompactor.Result compaction;
        private final StripedLocks.Handle lock;
        private long expectedVersion;

        private TurnSession(Conversation conversation,
                            List<MemoryRecaller.Hit> recallHits,
                            SummaryCompactor.Result compaction,
                            StripedLocks.Handle lock,
                            long expectedVersion) {
            this.conversation = conversation;
            this.recallHits = recallHits;
            this.compaction = compaction;
            this.lock = lock;
            this.expectedVersion = expectedVersion;
        }

        public Conversation conversation() {
            return conversation;
        }

        public List<MemoryRecaller.Hit> recallHits() {
            return recallHits;
        }

        public SummaryCompactor.Result compaction() {
            return compaction;
        }

        @Override
        public void close() {
            lock.close();
        }
    }

    public TurnSession beginTurn(String conversationId, String userId, String userText) {
        StripedLocks.Handle lock;
        try {
            lock = locks.acquire("conv:" + conversationId, Duration.ofSeconds(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("请求被中断,请重试");
        }
        if (lock == null) {
            throw new BusyException("上一条消息还在处理中,请等它结束后再发(同一会话串行处理)");
        }
        try {
            Conversation conv = store.load(conversationId)
                    .orElseGet(() -> Conversation.create(conversationId, userId));
            long expected = conv.getVersion();

            conv.appendUser(userText);

            // 先压缩再召回:压缩会把更早的轮次挪进归档,召回才有更大的候选池
            SummaryCompactor.Result compaction = compactor.maybeCompact(conv, false);
            if (compaction.compacted()) {
                store.appendArchive(conversationId, compaction.folded(),
                        props.getMemory().getArchiveMaxMessages());
            }

            List<MemoryRecaller.Hit> hits = List.of();
            if (conv.getSummaryCoveredSeq() > 0) {
                var archive = store.archive(conversationId, props.getMemory().getArchiveMaxMessages());
                hits = recaller.recall(userText, archive,
                        props.getMemory().getRecallTopK(), props.getMemory().getRecallMinScore());
            }

            persistInternal(conv, expected);
            return new TurnSession(conv, hits, compaction, lock, conv.getVersion());
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    /** 一轮中的增量落盘(每步 LLM 调用后调用一次) */
    public void persist(TurnSession session) {
        persistInternal(session.conversation, session.expectedVersion);
        session.expectedVersion = session.conversation.getVersion();
    }

    private void persistInternal(Conversation conv, long expectedVersion) {
        if (!store.save(conv, expectedVersion)) {
            // 说明存储里的版本已经变了:要么别的实例写了,要么本进程的另一次写入没走锁。
            // 宁可让用户重试,也不要把两份历史合并成一份四不像 —— 历史错乱的排查成本远高于一次重试。
            throw new ConcurrentUpdateException("会话状态已被更新,请重试本次消息");
        }
    }

    public Optional<Conversation> find(String conversationId) {
        return store.load(conversationId);
    }

    public List<Conversation> recent(String userId, int limit) {
        List<String> ids = store.listIds(userId, limit);
        List<Conversation> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            store.load(id).ifPresent(out::add);
        }
        return out;
    }

    public void delete(String conversationId) {
        store.delete(conversationId);
    }

    /** 手动压缩接口:加锁、压缩、归档、落盘,一步到位。 */
    public SummaryCompactor.Result compact(String conversationId, boolean force) {
        StripedLocks.Handle lock;
        try {
            lock = locks.acquire("conv:" + conversationId, Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("请求被中断,请重试");
        }
        if (lock == null) {
            throw new BusyException("会话正在处理中,请稍后再试");
        }
        try (lock) {
            Conversation conv = store.load(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
            long expected = conv.getVersion();
            SummaryCompactor.Result result = compactor.maybeCompact(conv, force);
            if (result.compacted()) {
                store.appendArchive(conversationId, result.folded(),
                        props.getMemory().getArchiveMaxMessages());
                persistInternal(conv, expected);
            }
            return result;
        }
    }

    public List<MemoryRecaller.Hit> recall(String conversationId, String query) {
        var archive = store.archive(conversationId, props.getMemory().getArchiveMaxMessages());
        return recaller.recall(query, archive, props.getMemory().getRecallTopK(),
                props.getMemory().getRecallMinScore());
    }

    public int archiveSize(String conversationId) {
        return store.archiveSize(conversationId);
    }

    public int tokensUsed(Conversation conv) {
        return TokenEstimator.estimateMemory(conv);
    }

    public ChatMemoryStore store() {
        return store;
    }

    public int activeLocks() {
        return locks.size();
    }

    public boolean summarizerBreakerOpen() {
        return compactor.breakerOpen();
    }
}
