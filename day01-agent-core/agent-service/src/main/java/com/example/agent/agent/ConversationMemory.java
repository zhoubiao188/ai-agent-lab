package com.example.agent.agent;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.dto.Msg;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆:进程内的滑动窗口。
 * <p>设计要点:</p>
 * <ul>
 *   <li>只保留最近 N 条,超窗从最旧的 user/assistant 对开始丢,<b>但永远不丢 system</b>;</li>
 *   <li>tool 消息成对保留 —— 丢了 assistant(tool_calls) 却留着 tool 结果,
 *       下一次请求会被模型侧以 400 拒掉,这是最经典的坑;</li>
 *   <li>加空闲过期,避免长跑进程内存泄漏。</li>
 * </ul>
 * 生产环境把 {@link #history} 换成 Redis List / 分布式缓存即可,接口不用变。
 */
@Component
public class ConversationMemory {

    private static final long IDLE_TTL_SECONDS = 3600;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final int window;

    public ConversationMemory(AgentProperties props) {
        this.window = Math.max(4, props.getMemoryWindow());
    }

    private static final class Session {
        final LinkedList<Msg> messages = new LinkedList<>();
        Instant lastAccess = Instant.now();
    }

    public List<Msg> history(String conversationId) {
        Session s = sessions.computeIfAbsent(conversationId, k -> new Session());
        s.lastAccess = Instant.now();
        synchronized (s) {
            return new ArrayList<>(s.messages);
        }
    }

    public void append(String conversationId, Msg... msgs) {
        Session s = sessions.computeIfAbsent(conversationId, k -> new Session());
        s.lastAccess = Instant.now();
        synchronized (s) {
            for (Msg m : msgs) {
                s.messages.addLast(m);
            }
            trim(s);
        }
    }

    public void clear(String conversationId) {
        sessions.remove(conversationId);
    }

    private void trim(Session s) {
        while (s.messages.size() > window) {
            Msg head = s.messages.peekFirst();
            s.messages.removeFirst();
            // 如果丢掉的是带 tool_calls 的 assistant 消息,把紧随其后的 tool 结果一并丢掉,
            // 保证消息序列始终合法
            if (head != null && head.hasToolCalls()) {
                while (!s.messages.isEmpty() && "tool".equals(s.messages.peekFirst().role())) {
                    s.messages.removeFirst();
                }
            }
        }
        // 窗口首条不能是孤立的 tool 消息
        while (!s.messages.isEmpty() && "tool".equals(s.messages.peekFirst().role())) {
            s.messages.removeFirst();
        }
    }

    /** 清理空闲会话,由定时任务调用。 */
    public int evictIdle() {
        Instant deadline = Instant.now().minusSeconds(IDLE_TTL_SECONDS);
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(deadline));
        return before - sessions.size();
    }

    public int sessionCount() {
        return sessions.size();
    }
}
