package com.example.memory.store;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级"分段锁"。
 *
 * <p>同一个会话的两次请求如果并发处理,会出现经典的读-改-写覆盖:</p>
 * <pre>
 * T1: load(v5) → append(user) → save(v6)
 * T2:          load(v5) → append(user) → save(v6)   ← 把 T1 的写入覆盖掉
 * </pre>
 * <p>用 {@code ConcurrentHashMap<String, ReentrantLock>} 把同一会话串行化,不同会话互不影响。
 * 这里不用 {@code synchronized(map)} 全局锁 —— 那会让所有会话排成一队,吞吐直接掉到个位数。</p>
 *
 * <p>引用计数是为了避免锁对象无限堆积:没有任何线程持有或等待时,把 entry 从 map 里摘掉。
 * 计数必须在 {@code compute} 里做,否则"摘除"和"新建"之间存在竞态,同一会话可能同时存在两把锁。</p>
 */
@Component
public class StripedLocks {

    private final ConcurrentHashMap<String, Holder> locks = new ConcurrentHashMap<>();

    private static final class Holder {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refs = new AtomicInteger();
    }

    public static final class Handle implements AutoCloseable {
        private final Holder holder;
        private final String key;
        private final StripedLocks owner;
        private boolean released;

        private Handle(StripedLocks owner, String key, Holder holder) {
            this.owner = owner;
            this.key = key;
            this.holder = holder;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            holder.lock.unlock();
            owner.release(key, holder);
        }
    }

    /**
     * 尝试在 timeout 内拿到锁。
     *
     * @return 拿到返回句柄(用完必须 close);超时返回 null,由调用方决定是排队还是直接拒绝
     */
    public Handle acquire(String key, Duration timeout) throws InterruptedException {
        Holder holder = locks.compute(key, (k, v) -> {
            Holder h = v == null ? new Holder() : v;
            h.refs.incrementAndGet();
            return h;
        });
        boolean ok;
        try {
            ok = holder.lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            release(key, holder);
            throw e;
        }
        if (!ok) {
            release(key, holder);
            return null;
        }
        return new Handle(this, key, holder);
    }

    private void release(String key, Holder holder) {
        locks.compute(key, (k, v) -> {
            if (v != holder) {
                return v;
            }
            return holder.refs.decrementAndGet() <= 0 ? null : holder;
        });
    }

    /** 仅供监控:当前有多少个会话持有/等待锁 */
    public int size() {
        return locks.size();
    }
}
