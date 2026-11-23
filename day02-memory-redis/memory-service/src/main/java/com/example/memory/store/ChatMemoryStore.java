package com.example.memory.store;

import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;

import java.util.List;
import java.util.Optional;

/**
 * 会话记忆存储抽象。
 *
 * <p>接口刻意只有 8 个方法,但每个方法都带着"生产上必须回答的问题":</p>
 * <ul>
 *   <li>{@link #save} 的 {@code expectedVersion} —— 多实例部署时怎么防止后写的覆盖先写的?</li>
 *   <li>{@link #appendArchive} 的 {@code maxMessages} —— 归档会不会无限增长?</li>
 *   <li>{@link #archive} 的 {@code limit} —— 召回时扫多少条才不会拖慢首字延迟?</li>
 *   <li>{@link #conversationCount} —— 内存实现要能回答"现在有多少会话",否则没法做容量告警。</li>
 * </ul>
 *
 * <p>默认实现是进程内的 {@link InMemoryChatMemoryStore},零依赖、开箱即跑;
 * 打开 {@code agent.memory.store=redis} 就切到 {@link RedisChatMemoryStore},
 * 上层代码一行都不用改 —— 这是先定接口再选存储的价值。</p>
 */
public interface ChatMemoryStore {

    /** 存储类型,用于 /api/memory/stats 与日志 */
    String type();

    Optional<Conversation> load(String conversationId);

    /**
     * 保存会话。
     *
     * @param expectedVersion 读取时的版本号;与存储中不一致说明被别人改过,返回 false 而不是覆盖
     * @return 是否保存成功
     */
    boolean save(Conversation conversation, long expectedVersion);

    void delete(String conversationId);

    /** 按更新时间倒序列出某用户的会话 id */
    List<String> listIds(String userId, int limit);

    /** 追加归档消息(压缩时被折叠掉的原文),超过上限从最旧的开始丢 */
    void appendArchive(String conversationId, List<Msg> messages, int maxMessages);

    /** 取最近 N 条归档消息,按时间正序返回 */
    List<Msg> archive(String conversationId, int limit);

    int archiveSize(String conversationId);

    long conversationCount();
}
