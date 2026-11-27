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
 * 全局请求追踪:生成 X-Request-Id 并透传给下游,让网关日志、服务日志、工具调用日志能串成一条链。
 *
 * <p>SSE 场景额外补两个响应头。少了它们,部分 Nginx / 浏览器会把事件流攒着,
 * 表现就是"前端一个字都不出,最后一次性全吐出来"——看起来像后端卡住,其实是缓冲。</p>
 *
 * <p>这里特意<b>不做</b>网关级重试。网关重试看起来省事,实际很危险:
 * 它看不到工具是不是幂等的、也不知道下游是不是已经执行了一半,
 * 一次超时重试就可能变成两笔退款。重试必须待在知道语义的那一层。</p>
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
