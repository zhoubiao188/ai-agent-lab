# Spring Cloud Gateway 实战

## 路由断言的匹配顺序

Gateway 的路由按配置文件中的声明顺序匹配,而不是按精确度。一旦某条路由的断言命中就直接转发,后面的路由不再参与。所以在网关里写 Path=/api/** 这种宽泛断言时,必须把它放在最后,否则 /api/admin/** 的专用路由永远不生效。排查转发错乱时先看 /actuator/gateway/routes,拿到实际注册顺序,比读 yml 靠谱。

## 过滤器链与执行顺序

GlobalFilter 与 GatewayFilter 会合并成一条链,按 Ordered 排序。order 值小的先执行 pre 逻辑,后执行 post 逻辑,像栈一样。RequestIdFilter 一般设成 -100 保证最早进入、最晚退出,日志才能覆盖完整耗时。需要修改响应体的过滤器(比如统一包装返回结构)必须实现 Ordered 并且 order 大于 NettyWriteResponseFilter(-1),否则只能看到空响应体。

## SSE 流式输出被网关缓冲

前端接大模型流式输出,最典型的故障是"等了十秒一次性吐出全部内容"。原因是网关或 Nginx 开了响应缓冲,把 chunked 数据攒起来再发。修复要三件事同时做:响应头设置 Cache-Control: no-cache, no-transform;设置 X-Accel-Buffering: no 告诉 Nginx 不要缓冲;网关的 httpclient.response-timeout 必须大于后端最长生成长度对应的耗时,否则 504 就在中途断掉。

## 限流器的两种维度

RequestRateLimiter 基于 Redis 令牌桶,按 KeyResolver 分出限流维度。按 IP 限流防刷,但办公室出口 NAT 会让整个公司共用一个桶;按用户 ID 限流更精准,前提是网关已经解析出身份;按 API Key 限流适合开放平台。默认的 replenishRate 是每秒补充令牌数,burstCapacity 是桶容量,前者决定长期速率,后者决定允许的瞬时尖峰,两个值都设成一样就没有任何突发容忍度。

## 超时与重试的陷阱

网关的重试只对幂等方法安全。默认 Retry 过滤器会在连接异常、5xx 时重放请求,如果下游是非幂等的下单接口,一次用户点击可能变成两次扣款。正确做法是只对 GET 开启重试,或者在下游做幂等键校验。另外 response-timeout 单位在配置里是 Duration 类型,写 300 不带单位会被当成 300 毫秒,这个错误非常隐蔽。

## 灰度发布与权重路由

用 Weight 断言做灰度:同一个 Path 声明两条路由,name 相同、weight 不同,例如 90/10。网关按权重随机选路,不需要引入额外的注册中心元数据。要注意 session 粘性,如果砍价、秒杀这类有状态业务被随机分流到两个版本,会出现状态错乱。此时应该把灰度维度收敛到用户 ID 取模,而不是纯随机。

## 跨域配置的位置

跨域应该只在一个地方配置。网关配了 globalcors,后端又配了 @CrossOrigin,响应里就会出现两个 Access-Control-Allow-Origin 头,浏览器直接拒绝。用 DedupeResponseHeader 过滤器可以去重,但更好的做法是明确分层:对外由网关统一处理跨域,内网服务一律不配跨域。预检请求 OPTIONS 默认被网关透传,后端如果没有处理会返回 405,需要在网关层直接返回 200。

## 请求体过大与上传

默认的 max-in-memory-size 对网关的请求体缓存有上限,超过会抛 DataBufferLimitException。文件上传接口要么在网关对这些路径单独放开限制,要么改走对象存储的直传签名,让浏览器绕过网关直接把文件 PUT 到 OSS。后者的好处是不占用网关带宽,坏处是需要处理签名过期与回调校验。

## 与注册中心配合的服务发现

spring.cloud.gateway.discovery.locator.enabled=true 可以自动为每个服务生成 /服务名/** 的路由。生产环境不建议直接开启,因为你无法控制上游服务暴露哪些接口。更稳的做法是显式声明路由,uri 写 lb://服务名,由 LoadBalancer 从注册中心拿实例列表,再配合 Retry 与熔断器做故障隔离。
