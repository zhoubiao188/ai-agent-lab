package com.example.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全局请求追踪:为每个请求生成 X-Request-Id,并透传给下游。
 * <p>SSE 场景下额外补两个响应头 —— 少了它们,部分 Nginx/浏览器会把事件流缓存住,
 * 表现就是"前端一句话都不出,最后一次性全吐出来"。</p>
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String rid = requestId;

        ServerHttpRequest mutated = request.mutate().header(HEADER, rid).build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(HEADER, rid);

        if (isEventStream(request)) {
            response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
            response.getHeaders().set("X-Accel-Buffering", "no");
        }

        String path = request.getURI().getPath();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> log.info("{} {} -> {} ({}ms) rid={}",
                        request.getMethod(), path, response.getStatusCode(),
                        System.currentTimeMillis() - start, rid));
    }

    private boolean isEventStream(ServerHttpRequest request) {
        String accept = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/event-stream");
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
