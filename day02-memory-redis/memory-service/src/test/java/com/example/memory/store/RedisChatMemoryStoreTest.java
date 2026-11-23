package com.example.memory.store;

import com.example.memory.Fixtures;
import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 实现的集成测试 —— 需要一个真实的 Redis。
 *
 * <p>跑法:</p>
 * <pre>
 * redis-server --port 6390 --daemonize yes
 * REDIS_TEST_PORT=6390 mvn test -Dtest=RedisChatMemoryStoreTest
 * </pre>
 *
 * <p>没起 Redis 时自动跳过(Assumptions),不会让 CI 红。</p>
 *
 * <p>为什么非要真连:序列化、Lua CAS、LIST 的 LTRIM 边界、TTL 单位(秒 vs 毫秒)——
 * 这些用 mock 测不出来,而恰恰是上线后最先炸的地方。</p>
 */
class RedisChatMemoryStoreTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static boolean available;
    private static RedisChatMemoryStore store;

    @BeforeAll
    static void connect() {
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_TEST_PORT", "6390"));
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("localhost", port);
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.getConnectionFactory().getConnection().ping();
            available = true;
        } catch (Exception e) {
            available = false;
        }
        AgentProperties props = Fixtures.props();
        store = new RedisChatMemoryStore(redis, Fixtures.MAPPER, props);
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void clean() {
        Assumptions.assumeTrue(available, "本机没有可用的 Redis,跳过集成测试");
        var keys = redis.keys("agent:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("会话 JSON 往返:消息、摘要、关键事实、版本号都不丢")
    void saveAndLoadRoundTrip() {
        Conversation c = Conversation.create("c-" + UUID.randomUUID(), "u1");
        c.appendUser("订单号 A20261123001 退款 899.00 元");
        c.appendAssistant("已记录", null);
        c.setSummary("【用户目标】查询退款");
        c.setSummaryCoveredSeq(1);
        c.mergePinnedFacts(List.of("单号 A20261123001"));

        assertThat(store.save(c, 0)).isTrue();

        Conversation loaded = store.load(c.getId()).orElseThrow();
        assertThat(loaded.getMessages()).hasSize(2);
        assertThat(loaded.getMessages().get(0).content()).contains("A20261123001");
        assertThat(loaded.getSummary()).isEqualTo("【用户目标】查询退款");
        assertThat(loaded.getPinnedFacts()).containsExactly("单号 A20261123001");
        assertThat(loaded.getVersion()).isEqualTo(c.getVersion());
        assertThat(store.type()).isEqualTo("redis");
    }

    @Test
    @DisplayName("Lua CAS:过期版本写入被拒绝,数据保持第一次写入的内容")
    void casRejectsStaleWrite() {
        Conversation a = Conversation.create("c-cas", "u1");
        a.appendUser("A 写入");
        assertThat(store.save(a, 0)).isTrue();

        Conversation b = Conversation.create("c-cas", "u1");
        b.appendUser("B 覆盖");
        assertThat(store.save(b, 0)).isFalse();

        assertThat(store.load("c-cas").orElseThrow().getMessages().get(0).content()).isEqualTo("A 写入");
    }

    @Test
    @DisplayName("归档写入超上限时 LTRIM 只留最近的")
    void archiveTrim() {
        store.appendArchive("c-arch", List.of(
                Msg.user(1, "m1"), Msg.user(2, "m2"), Msg.user(3, "m3"), Msg.user(4, "m4")), 2);

        assertThat(store.archiveSize("c-arch")).isEqualTo(2);
        assertThat(store.archive("c-arch", 10)).extracting(Msg::content).containsExactly("m3", "m4");
    }

    @Test
    @DisplayName("TTL 被正确设置(毫秒级写入,不是秒)")
    void ttlIsSet() {
        Conversation c = Conversation.create("c-ttl", "u1");
        c.appendUser("hi");
        store.save(c, 0);

        Long ttl = redis.getExpire("agent:conv:c-ttl", java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofDays(1).toSeconds());
    }

    @Test
    @DisplayName("按用户列会话,最近更新的在前")
    void listByUser() throws InterruptedException {
        Conversation c1 = Conversation.create("c-1", "user-x");
        c1.appendUser("早的");
        store.save(c1, 0);
        Thread.sleep(20);
        Conversation c2 = Conversation.create("c-2", "user-x");
        c2.appendUser("晚的");
        store.save(c2, 0);

        assertThat(store.listIds("user-x", 10)).containsExactly("c-2", "c-1");
        assertThat(store.listIds("nobody", 10)).isEmpty();
    }

    @Test
    @DisplayName("删除会话同时清掉版本号、归档与用户索引")
    void deleteCleansUp() {
        Conversation c = Conversation.create("c-del", "user-y");
        c.appendUser("hi");
        store.save(c, 0);
        store.appendArchive("c-del", List.of(Msg.user(1, "old")), 10);

        store.delete("c-del");

        assertThat(store.load("c-del")).isEmpty();
        assertThat(store.archiveSize("c-del")).isZero();
        assertThat(store.listIds("user-y", 10)).isEmpty();
    }

    @Test
    @DisplayName("会话计数用 SCAN 统计,不受其他 key 干扰")
    void conversationCount() {
        for (int i = 0; i < 3; i++) {
            Conversation c = Conversation.create("c-count-" + i, "u1");
            c.appendUser("hi " + i);
            store.save(c, 0);
        }
        assertThat(store.conversationCount()).isEqualTo(3);
    }
}
