package com.example.memory.store;

import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 实现:会话状态、版本号、归档、用户会话索引四类 key。
 *
 * <pre>
 * agent:conv:{id}          STRING  会话 JSON(含活跃窗口 + 摘要 + 关键事实)
 * agent:conv:{id}:ver      STRING  版本号,用于 CAS
 * agent:conv:{id}:archive  LIST    被压缩掉的原文,LPUSH/RPUSH 均可,这里用 RPUSH + LTRIM
 * agent:user:{uid}:convs   ZSET    score=updatedAt,用于"最近会话列表"
 * </pre>
 *
 * <p><b>为什么 CAS 一定要用 Lua。</b>如果写成 GET → 比较 → SET,两个实例完全可能同时 GET 到
 * version=7,然后都认为自己是合法的,后 SET 的把先 SET 的覆盖掉 —— 用户会看到"我发的消息
 * 莫名其妙消失了"。Lua 在 Redis 里是单线程原子执行的,比较和写入之间不可能被插入。</p>
 *
 * <p><b>为什么归档用 LIST 而不是塞进会话 JSON。</b>每次对话都要整体读写会话对象,
 * 把 400 条历史塞进去会让单次往返从 2KB 涨到上百 KB;而归档只在"压缩"和"召回"两个低频路径上访问,
 * 分开存可以让主路径保持轻量。</p>
 */
@Component
@ConditionalOnProperty(name = "agent.memory.store", havingValue = "redis")
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    private static final String CONV_PREFIX = "agent:conv:";
    private static final String VER_SUFFIX = ":ver";
    private static final String ARCHIVE_SUFFIX = ":archive";
    private static final String USER_PREFIX = "agent:user:";
    private static final String INDEX_SUFFIX = ":convs";

    /**
     * KEYS[1]=conv KEYS[2]=ver KEYS[3]=userIndex
     * ARGV[1]=expectedVersion ARGV[2]=json ARGV[3]=ttlMs ARGV[4]=newVersion ARGV[5]=score ARGV[6]=convId
     */
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[2])
            if cur and tonumber(cur) ~= tonumber(ARGV[1]) then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[3])
            redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long ttlMillis;

    public RedisChatMemoryStore(StringRedisTemplate redis, ObjectMapper mapper, AgentProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttlMillis = props.getMemory().getTtl().toMillis();
    }

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public Optional<Conversation> load(String conversationId) {
        String json = redis.opsForValue().get(convKey(conversationId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, Conversation.class));
        } catch (Exception e) {
            log.error("会话反序列化失败,按空会话处理 cid={}", conversationId, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean save(Conversation conversation, long expectedVersion) {
        try {
            String json = mapper.writeValueAsString(conversation);
            Long result = redis.execute(SAVE_SCRIPT,
                    List.of(convKey(conversation.getId()),
                            verKey(conversation.getId()),
                            indexKey(conversation.getUserId())),
                    String.valueOf(expectedVersion),
                    json,
                    String.valueOf(ttlMillis),
                    String.valueOf(conversation.getVersion()),
                    String.valueOf(conversation.getUpdatedAt()),
                    conversation.getId());
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("会话保存失败 cid={}", conversation.getId(), e);
            return false;
        }
    }

    @Override
    public void delete(String conversationId) {
        Optional<Conversation> conv = load(conversationId);
        redis.delete(List.of(convKey(conversationId), verKey(conversationId), archiveKey(conversationId)));
        conv.ifPresent(c -> redis.opsForZSet().remove(indexKey(c.getUserId()), conversationId));
    }

    @Override
    public List<String> listIds(String userId, int limit) {
        Set<String> ids = redis.opsForZSet()
                .reverseRange(indexKey(userId == null ? "anonymous" : userId), 0, Math.max(0, limit - 1));
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    @Override
    public void appendArchive(String conversationId, List<Msg> messages, int maxMessages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            List<String> jsons = new ArrayList<>(messages.size());
            for (Msg m : messages) {
                jsons.add(mapper.writeValueAsString(m));
            }
            String key = archiveKey(conversationId);
            redis.opsForList().rightPushAll(key, jsons);
            // 只保留最近 maxMessages 条:归档是"可能有用",不是"永久账本"
            redis.opsForList().trim(key, -Math.max(1, maxMessages), -1);
            redis.expire(key, java.time.Duration.ofMillis(ttlMillis));
        } catch (Exception e) {
            log.error("归档写入失败 cid={}", conversationId, e);
        }
    }

    @Override
    public List<Msg> archive(String conversationId, int limit) {
        List<String> jsons = redis.opsForList().range(archiveKey(conversationId), -Math.max(1, limit), -1);
        if (jsons == null || jsons.isEmpty()) {
            return List.of();
        }
        List<Msg> out = new ArrayList<>(jsons.size());
        for (String json : jsons) {
            try {
                out.add(mapper.readValue(json, Msg.class));
            } catch (Exception e) {
                log.warn("归档消息反序列化失败,跳过: {}", e.getMessage());
            }
        }
        return out;
    }

    @Override
    public int archiveSize(String conversationId) {
        Long size = redis.opsForList().size(archiveKey(conversationId));
        return size == null ? 0 : size.intValue();
    }

    /** 用 SCAN 而不是 KEYS:KEYS 会阻塞整个 Redis 实例,生产环境是红线。 */
    @Override
    public long conversationCount() {
        long count = 0;
        ScanOptions options = ScanOptions.scanOptions().match(CONV_PREFIX + "*" + VER_SUFFIX).count(200).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private String convKey(String id) {
        return CONV_PREFIX + id;
    }

    private String verKey(String id) {
        return CONV_PREFIX + id + VER_SUFFIX;
    }

    private String archiveKey(String id) {
        return CONV_PREFIX + id + ARCHIVE_SUFFIX;
    }

    private String indexKey(String userId) {
        return USER_PREFIX + (userId == null ? "anonymous" : userId) + INDEX_SUFFIX;
    }
}
