package com.example.memory.ratelimit;

import com.example.memory.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流。
 * <p>LLM 调用是"按 token 计费"的昂贵操作,一个失控的脚本能把账单打穿。
 * 这里做的是应用级的最小可用版本(单机令牌桶);多实例部署时换成 Redis + Lua 即可,
 * 拦截点不用动 —— 这也是把它放在 Filter 而不是业务代码里的原因。</p>
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerNano;

    public RateLimitFilter(AgentProperties props) {
        this.capacity = Math.max(1, props.getRateLimitPerMinute());
        // 每分钟补满一桶
        this.refillPerNano = capacity / 60_000_000_000.0;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/chat");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = clientKey(request);
        if (!tryAcquire(key)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "3");
            response.getWriter().write("""
                    {"error":"rate_limited","message":"请求过于频繁,请稍后再试(每分钟 %d 次)"}"""
                    .formatted(capacity));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tryAcquire(String key) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastRefillNanos) * refillPerNano;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private String clientKey(HttpServletRequest request) {
        String clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) {
            return "c:" + clientId;
        }
        return "ip:" + request.getRemoteAddr();
    }
}
