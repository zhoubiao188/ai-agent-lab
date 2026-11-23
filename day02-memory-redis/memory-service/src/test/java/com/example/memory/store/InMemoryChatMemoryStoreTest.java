package com.example.memory.store;

import com.example.memory.Fixtures;
import com.example.memory.config.AgentProperties;
import com.example.memory.domain.Conversation;
import com.example.memory.domain.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryChatMemoryStoreTest {

    private AgentProperties props;
    private InMemoryChatMemoryStore store;

    @BeforeEach
    void setUp() {
        props = Fixtures.props();
        store = new InMemoryChatMemoryStore(Fixtures.MAPPER, props);
    }

    @Test
    @DisplayName("新建会话用 expectedVersion=0 保存,再读出来内容一致")
    void saveAndLoad() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("你好");
        c.appendAssistant("你好,有什么可以帮你?", null);

        assertThat(store.save(c, 0)).isTrue();

        Conversation loaded = store.load("c1").orElseThrow();
        assertThat(loaded.getMessages()).hasSize(2);
        assertThat(loaded.getTitle()).isEqualTo("你好");
        assertThat(loaded.getVersion()).isEqualTo(c.getVersion());
    }

    @Test
    @DisplayName("版本不匹配时拒绝写入,防止并发覆盖")
    void rejectStaleWrite() {
        Conversation a = Conversation.create("c1", "u1");
        a.appendUser("A 的消息");
        assertThat(store.save(a, 0)).isTrue();

        Conversation b = Conversation.create("c1", "u1");
        b.appendUser("B 的消息");
        // B 基于 version=0 写入,但存储里已经是 1 了
        assertThat(store.save(b, 0)).isFalse();

        assertThat(store.load("c1").orElseThrow().getMessages().get(0).content()).isEqualTo("A 的消息");
    }

    @Test
    @DisplayName("load 返回深拷贝,改返回值不会污染存储")
    void loadReturnsCopy() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("原始消息");
        store.save(c, 0);

        Conversation loaded = store.load("c1").orElseThrow();
        loaded.appendUser("偷偷加的消息");

        assertThat(store.load("c1").orElseThrow().getMessages()).hasSize(1);
    }

    @Test
    @DisplayName("归档超过上限时丢最旧的")
    void archiveTrim() {
        List<Msg> msgs = List.of(
                Msg.user(1, "m1"), Msg.user(2, "m2"), Msg.user(3, "m3"),
                Msg.user(4, "m4"), Msg.user(5, "m5"));

        store.appendArchive("c1", msgs, 3);

        assertThat(store.archiveSize("c1")).isEqualTo(3);
        List<Msg> kept = store.archive("c1", 10);
        assertThat(kept).extracting(Msg::content).containsExactly("m3", "m4", "m5");
    }

    @Test
    @DisplayName("会话列表按更新时间倒序")
    void listSortedByUpdatedAt() throws InterruptedException {
        Conversation c1 = Conversation.create("c1", "u1");
        c1.appendUser("第一个");
        store.save(c1, 0);

        Thread.sleep(5);

        Conversation c2 = Conversation.create("c2", "u1");
        c2.appendUser("第二个");
        store.save(c2, 0);

        assertThat(store.listIds("u1", 10)).containsExactly("c2", "c1");
        assertThat(store.listIds("other", 10)).isEmpty();
    }

    @Test
    @DisplayName("删除会话会同时清掉归档")
    void deleteRemovesArchive() {
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("你好");
        store.save(c, 0);
        store.appendArchive("c1", List.of(Msg.user(1, "旧消息")), 10);

        store.delete("c1");

        assertThat(store.load("c1")).isEmpty();
        assertThat(store.archiveSize("c1")).isZero();
        assertThat(store.conversationCount()).isZero();
    }

    @Test
    @DisplayName("过期会话被清理任务回收")
    void evictExpired() throws InterruptedException {
        props.getMemory().setTtl(java.time.Duration.ofMillis(30));
        InMemoryChatMemoryStore shortLived = new InMemoryChatMemoryStore(Fixtures.MAPPER, props);
        Conversation c = Conversation.create("c1", "u1");
        c.appendUser("你好");
        shortLived.save(c, 0);

        Thread.sleep(60);
        shortLived.evictExpired();

        assertThat(shortLived.load("c1")).isEmpty();
        assertThat(shortLived.conversationCount()).isZero();
    }
}
