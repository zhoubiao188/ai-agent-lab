# 从零手写 AI Agent(二):Redis 会话记忆 + 滚动摘要压缩,让 50 轮对话不失忆、不烧钱

> 系列第 2 篇。上一篇我们把 Agent 的编排核心(SSE 流式 + Function Calling)从零写了一遍;
> 这一篇解决它上线后第一个必然遇到的问题:**聊到第 20 轮,它把你第 3 轮说的话忘了。**
>
> 完整代码:https://github.com/zhoubiao188/ai-agent-lab/tree/main/day02-memory-redis

## 一、先看一个真实翻车现场

上周有读者在群里贴了一段对话记录,大意是这样:

```
用户(第 3 轮):  订单号 A20261123001,退款 899 元,帮我盯着点
助手:            好的,已记录订单 A20261123001,退款 899 元
...
用户(第 20 轮): 那个订单现在到哪一步了?
助手:            请问您说的是哪个订单?方便提供一下订单号吗?
```

用户当场就炸了。这不是模型笨,是**我们没把记忆做对**。

Day 01 里的 `ConversationMemory` 是一个滑动窗口:只保留最近 20 条消息,超了就从最旧的开始丢。
它有两个致命缺陷:

1. **丢得没脑子**。第 3 轮的订单号和第 19 轮的"嗯嗯"在它眼里一样重,先来的先死。
2. **丢了就真没了**。进程一重启、换一台实例、用户换个浏览器,记忆直接归零 —— 因为它存在 `ConcurrentHashMap` 里。

这篇文章要把这块彻底重做:会话落 Redis,上下文用**滚动摘要**压缩,被压掉的原文进归档,
需要时还能按相关度**召回**。

## 二、先算笔账:50 轮对话到底有多大

不要凭感觉优化,先量化。

一条消息的 token 粗略等于「汉字数 × 1 + 其他字符数 ÷ 3.6」,再加每条 4 token 的结构开销。
一个真实的客服场景,单条消息平均 60~80 token,一轮(问 + 答)约 140 token,如果触发工具调用
还要加上工具声明和返回结果,一轮能到 300 token。

| 轮次 | 全量回放 token | 说明 |
| --- | --- | --- |
| 10 轮 | ≈ 1,400 | 完全无压力 |
| 30 轮 | ≈ 4,200 | 开始明显变慢、变贵 |
| 50 轮 | ≈ 7,000 ~ 15,000 | 接近甚至超过小模型的上下文窗口 |
| 200 轮 | ≈ 30,000+ | 必然报错,或者账单爆炸 |

三个后果,一个比一个严重:

1. **成本线性上涨**。每一轮都要为全部历史付费,50 轮对话的总成本是 10 轮的 25 倍(平方级)。
2. **首字延迟上涨**。prompt 越长,prefill 越慢。用户体感是"它越来越迟钝"。
3. **直接失败**。超过模型窗口,网关返回 400 或 413,而且报错信息通常极其含糊。

所以问题不是"要不要压缩",而是"怎么压缩才不丢信息"。

## 三、三种方案,以及为什么选第三种

| 方案 | 做法 | 优点 | 致命问题 |
| --- | --- | --- | --- |
| 全量回放 | 所有历史都发 | 信息 100% 保留 | 成本/延迟/窗口三杀 |
| 滑动窗口 | 只留最近 N 条 | 实现 5 行,稳定 | 早期关键信息硬丢失,体验断崖 |
| 滚动摘要 | 老历史压成摘要,新历史保留原文 | 上下文恒定,信息可延续 | 摘要会漂移,需要额外机制兜底 |

我们选第三种,但要解决它的三个副作用:

- **摘要漂移**:压 5 次之后,"订单 A20261123001" 变成"该订单","899.00 元" 变成"约 900 元"。
- **摘要失真**:模型可能把"用户可能想退货"写成"用户要退货"。
- **摘要服务本身会挂**:摘要也是一次 LLM 调用,它会超时、会 429、会欠费。

对应的三个设计:**关键事实双轨**、**结构化摘要模板**、**降级 + 熔断**。下面逐个展开。

## 四、整体架构

```
                        ┌──────────────────────────────────────────┐
   POST /api/chat/stream│  ChatController(SSE)                    │
                        └───────────────┬──────────────────────────┘
                                        ▼
                        ┌──────────────────────────────────────────┐
                        │  AgentOrchestrator(while 循环)          │
                        └───────────────┬──────────────────────────┘
                                        ▼
        ┌───────────────────────────────────────────────────────────────┐
        │  ConversationService.beginTurn()                              │
        │                                                               │
        │   1. 会话级锁(同会话串行,不同会话并行)                     │
        │   2. load 会话 / append 用户消息                              │
        │   3. SummaryCompactor.maybeCompact()                          │
        │        ├─ 水位判断(token 预算 × 0.72 或消息条数)             │
        │        ├─ 按「整轮」切分,折叠最早的 N 轮                      │
        │        ├─ 摘要模型:旧摘要 + 新折叠 → 新摘要(增量)          │
        │        ├─ 正则抽取关键事实 → pinnedFacts                      │
        │        └─ 原文 → 归档(Redis LIST,上限 400 条)              │
        │   4. MemoryRecaller.recall() → 相关旧片段                     │
        │   5. 落库(CAS)                                               │
        └───────────────────────────────────────────────────────────────┘
                                        ▼
        上下文 = 规则 + 工具清单 + 长期记忆摘要 + 关键事实 + 召回片段 + 活跃窗口
```

## 五、数据模型:为什么每条消息都要有 seq

Day 01 的 `Msg` 只有 `role/content/toolCalls`。这一版必须加一个字段:

```java
public record Msg(
        long seq,                 // 会话内单调递增序号
        String role,
        String content,
        String toolCallId,
        String name,
        List<ToolCall> toolCalls,
        long createdAt
) { }
```

`seq` 不是装饰。摘要需要记录"我到底覆盖到哪一条":

```java
private String summary;            // 滚动摘要正文
private long summaryCoveredSeq;    // seq <= 该值的消息都已折叠进 summary
```

如果用数组下标来记,消息一被裁剪下标就整体错位,摘要覆盖率立刻失真,于是要么重复压缩
(浪费钱),要么漏压(信息重复出现在摘要和窗口里,模型开始自相矛盾)。

会话本身是一个聚合根,把"活跃窗口"和"长期记忆"放在一起,保证一次读写就是一致状态:

```java
public class Conversation {
    private String id;
    private String userId;
    private String title;
    private long version;              // 乐观锁版本号
    private long nextSeq = 1;
    private List<Msg> messages;        // 活跃窗口(会发给模型的部分)
    private String summary;            // 被折叠历史的压缩结果
    private long summaryCoveredSeq;
    private List<String> pinnedFacts;  // 关键标识符,原文保真
    private Stats stats;               // 累计消息数 / 压缩次数 / 省下的 token
}
```

注意 `messages` 里**只有活跃窗口**,归档不在这里。原因很实际:每次对话都要整体读写这个对象,
把 400 条历史塞进去,单次 Redis 往返会从 2KB 涨到上百 KB,而归档只在"压缩"和"召回"两个
低频路径上访问。

## 六、存储层:先定接口,再选实现

```java
public interface ChatMemoryStore {
    String type();
    Optional<Conversation> load(String conversationId);

    /** expectedVersion 与存储中不一致时返回 false,而不是覆盖 */
    boolean save(Conversation conversation, long expectedVersion);

    void delete(String conversationId);
    List<String> listIds(String userId, int limit);

    void appendArchive(String conversationId, List<Msg> messages, int maxMessages);
    List<Msg> archive(String conversationId, int limit);
    int archiveSize(String conversationId);
    long conversationCount();
}
```

默认实现是进程内的 `InMemoryChatMemoryStore`(零依赖,clone 下来就能跑),配置
`agent.memory.store=redis` 切到 `RedisChatMemoryStore`,上层一行都不用改。

### 内存实现的两个细节

第一,**读写都做深拷贝**。如果直接把对象引用交出去,调用方改完没保存,内存里的"数据库"
就已经脏了 —— 这类 bug 只在"保存失败"的路径上暴露,极难排查。

```java
@Override
public Optional<Conversation> load(String conversationId) {
    Entry e = conversations.get(conversationId);
    ...
    synchronized (e) {
        return Optional.of(mapper.convertValue(e.conversation, Conversation.class));
    }
}
```

第二,**单机也不允许并发覆盖**。两个线程同时处理同一会话的两条消息,后完成的会把前一个
的写入抹掉。所以内存实现同样带版本号校验。

### Redis 实现:CAS 必须用 Lua

很多人会写成这样,然后线上丢消息:

```java
// 错误示范:GET 和 SET 之间有两个线程的窗口
Conversation cur = load(id);
if (cur.version == expected) {
    redis.set(key, json);   // ← 两个实例都通过了检查,后写的覆盖先写的
}
```

正确做法是把"比较 + 写入"塞进一段 Lua,Redis 单线程执行,中间不可能被插入:

```lua
local cur = redis.call('GET', KEYS[2])
if cur and tonumber(cur) ~= tonumber(ARGV[1]) then
  return 0
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])   -- 会话 JSON
redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[3])   -- 版本号
redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])        -- 用户会话索引,score=updatedAt
redis.call('PEXPIRE', KEYS[3], ARGV[3])
return 1
```

Java 侧:

```java
Long result = redis.execute(SAVE_SCRIPT,
        List.of(convKey(id), verKey(id), indexKey(userId)),
        String.valueOf(expectedVersion),   // ARGV[1]
        json,                              // ARGV[2]
        String.valueOf(ttlMillis),         // ARGV[3]
        String.valueOf(conv.getVersion()), // ARGV[4]
        String.valueOf(conv.getUpdatedAt()), conv.getId());
return result != null && result == 1L;
```

key 设计:

| key | 类型 | 用途 |
| --- | --- | --- |
| `agent:conv:{id}` | STRING | 会话 JSON(窗口 + 摘要 + 关键事实) |
| `agent:conv:{id}:ver` | STRING | 版本号,CAS 用 |
| `agent:conv:{id}:archive` | LIST | 被折叠的原文,RPUSH + LTRIM |
| `agent:user:{uid}:convs` | ZSET | 最近会话列表,score = updatedAt |

TTL 用 `PX` 而不是 `EX`。这不是洁癖:`EX` 只接受整数秒,配置里写 `Duration.ofMillis(500)`
会被静默截断成 0,然后 key 立刻过期 —— 这个坑我在测试里踩过一次,表现是"写完就读不到"。

## 七、会话级锁:别用全局锁

同一会话的两条消息并发处理,一定会互相覆盖。但用 `synchronized(map)` 做全局锁,所有会话
会排成一队,吞吐直接掉到个位数。正确做法是"分段锁":

```java
public Handle acquire(String key, Duration timeout) throws InterruptedException {
    // 引用计数必须在 compute 里做,否则"摘除"和"新建"之间存在竞态,
    // 同一会话可能同时存在两把锁 —— 那就等于没锁
    Holder holder = locks.compute(key, (k, v) -> {
        Holder h = v == null ? new Holder() : v;
        h.refs.incrementAndGet();
        return h;
    });
    if (!holder.lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        release(key, holder);
        return null;   // 超时,交给上层拒绝而不是无限排队
    }
    return new Handle(this, key, holder);
}
```

拿不到锁时返回 `null`,上层抛 `BusyException`,SSE 里下发一个可重试的错误事件。
**宁可让用户等一秒再发,也不要两条消息交错写坏历史。**

## 八、压缩器:四个必须做对的判断

### 8.1 判断一:什么时候压

```java
boolean overWatermark = before > cfg.getBudgetTokens() * cfg.getCompactThreshold()
        || conv.getMessages().size() > cfg.getMaxMessages();
```

两个条件取先到者:

- **token 水位**(默认 6000 × 0.72 ≈ 4300):控制成本与延迟;
- **消息条数**(默认 36):兜住"每条都很短但条数爆炸"的场景,比如连续的工具往返。

为什么是 0.72 而不是 1.0?因为压缩本身要花一次模型调用(几百毫秒),等到 100% 才压,
这一轮的请求已经变慢变贵了。留 28% 的缓冲,让压缩发生在"还没难受"的时候。

### 8.2 判断二:从哪儿切 —— 这是最容易写错的地方

先看一段线上最常见的报错:

```
400 Bad Request
messages with role 'tool' must be a response to a preceding message with 'tool_calls'
```

根因几乎总是同一个:压缩时按"消息条数"裁剪,把这一对拆散了。

```
assistant(tool_calls=[call_1])   ← 被删了
tool(tool_call_id=call_1)        ← 留下了
```

解法不是"删完再修补",而是**从结构上让这种序列不可能出现**:以 user 消息为边界把会话
切成若干"轮",删除只以轮为单位进行。

```java
public static List<Segment> segment(List<Msg> messages) {
    List<Segment> segments = new ArrayList<>();
    List<Msg> current = new ArrayList<>();
    for (Msg m : messages) {
        if (m.isUser() && !current.isEmpty()) {
            segments.add(toSegment(current));
            current = new ArrayList<>();
        }
        current.add(m);
    }
    if (!current.isEmpty()) {
        segments.add(toSegment(current));
    }
    return segments;
}
```

一轮的边界长这样,工具调用和它的结果永远在同一个 Segment 里:

```
┌─ Segment(seq 1..4) ─────────────────────────────┐
│ user      : 我要退款                            │
│ assistant : [tool_calls: create_support_ticket] │
│ tool      : 工单号 T20261123001                 │
│ assistant : 已创建,24 小时内联系您              │
└─────────────────────────────────────────────────┘
```

这样还顺带保证了压缩后的窗口**第一条永远是 user**,序列合法性一眼可验 —— 我在测试里就是这么断言的。

### 8.3 判断三:保留多少

从尾部往前累加,两个条件**同时**满足才停:轮数够、token 够。

```java
private int chooseCut(List<Segment> segments, AgentProperties.Memory cfg) {
    int cut = segments.size();
    int keptSegments = 0, keptTokens = 0;
    while (cut > 1) {
        boolean needMore = keptSegments < cfg.getKeepMinSegments()
                || keptTokens < cfg.getKeepTailTokens();
        if (!needMore) break;
        keptTokens += segments.get(cut - 1).tokens();
        keptSegments++;
        cut--;
    }
    return cut;
}
```

为什么不是"留够 token 就行":如果最后两轮特别长(比如一次并行调了 5 个工具),只按 token
算可能只留 1 轮,用户会感觉"我刚说的话它就没印象了"。`keepMinSegments` 是体验兜底。

### 8.4 判断四:压完真的变小了吗

这一条很多人不写,但它能省掉大量无意义的模型调用:

```java
int keptTokens = conv.getMessages().stream()
        .filter(m -> m.seq() > coveredSeq)
        .mapToInt(TokenEstimator::estimate)
        .sum();
int projected = TokenEstimator.estimate(newSummary) + keptTokens;
if (!force && projected >= before) {
    log.debug("会话 {} 压缩收益为负({} -> {}),跳过", conv.getId(), before, projected);
    return Result.skipped(before, "not_profitable");
}
```

摘要本身也有成本(默认上限 700 字)。如果折叠掉的内容还没摘要长,这次压缩纯粹是白花一次
调用,还让摘要多漂移一次。**回滚,保持原状态。**

## 九、摘要 prompt:结构化模板 + 增量合并

摘要不是"总结一下",它要产出的是**下一轮可以直接使用的记忆**。所以 prompt 里全是硬约束:

```
【会话记忆压缩任务】
你是一个会话记忆压缩器,负责把长对话压缩成可供后续轮次继续使用的摘要。

1. 只输出摘要正文。不要解释、不要客套、不要代码块围栏、不要"好的"开头。
2. 严格保留所有数字、单号、ID、金额、日期、时间、人名、地名、产品型号的原文,
   不得改写、不得四舍五入、不得省略单位。
3. 按下面的固定小节输出,没有内容就写"无":
   【用户目标】【已确认事实】【已做决策】【待办事项】【未解决问题】
4. 新信息与旧摘要冲突时,以新信息为准,并在该条后标注"(已更新)"。
5. 总长度不超过 600 字。不要编造对话中不存在的信息。
6. 不要写"用户询问了……助手回答了……"这类流水账,只写结论与状态。
```

三个要点:

- **固定小节**让摘要可读、可比对、可测试。没有结构,每次压缩的产物形态都不一样,
  下游根本没法用;
- **"冲突以新信息为准"** 是必须的。用户第 3 轮说"要退款",第 10 轮说"算了换货",
  没有这条规则,摘要会同时保留两个互相矛盾的决策;
- **"不写流水账"** 直接影响摘要密度。放任模型写"用户询问了 A,助手回答了 B",
  600 字只能装 5 轮。

关键是**增量**:不重新总结全部历史,而是把「已有摘要」和「本次要折叠的对话」合并:

```java
private String buildUserPrompt(String oldSummary, List<Segment> folded) {
    sb.append("【已有摘要】\n");
    sb.append(oldSummary.isBlank() ? "(无,这是第一次压缩)" : oldSummary.trim());
    sb.append("\n\n【本次要合并进摘要的对话】\n");
    for (Segment s : folded) {
        sb.append(s.digest(400));      // 单条消息截断 400 字
    }
    sb.append("\n请输出合并后的新摘要。");
}
```

一次压缩的输入 ≈ 旧摘要(≤700 字)+ 被折叠的几轮对话,输出 ≤700 字,**和会话总长度无关**。
这是它能撑住 50 轮甚至 500 轮的根本原因。

工具返回的原始 JSON 动辄上千字,但它对"记住用户意图"几乎没有价值,所以 `digest` 里
单条消息截断到 400 字 —— 压缩输入也要控成本。

## 十、关键事实:用确定性手段兜住概率性组件

纯 LLM 摘要压 5 次,"A20261123001" 必然变成"该订单"。这在客服场景是事故,在财务场景是灾难。

所以关键标识符**不交给模型记**:

```java
private static final Pattern ORDER_NO = Pattern.compile(
        "(?:订单|工单|退款单|运单|票号|流水号|编号|合同|账号)[号码]?\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9\\-_]{5,})");
private static final Pattern AMOUNT = Pattern.compile(
        "(?:[¥￥$]\\s?\\d+(?:\\.\\d+)?)|(?:\\d+(?:\\.\\d+)?\\s?(?:元|块钱|块|万元|美元))");
private static final Pattern DATE = Pattern.compile(
        "\\d{4}\\s?[-/.年]\\s?\\d{1,2}\\s?[-/.月]\\s?\\d{1,2}\\s?日?|\\d{1,2}月\\d{1,2}[日号]");
```

规则提取的召回率不如模型,但它 **100% 保真、零成本、可单测**。每轮请求都原样注入 system:

```
【必须记住的关键信息(原文,不得改写)】
- 单号 A20261123001
- 金额 899.00 元
- 日期 2026-11-25
```

这就是"用确定性手段兜住概率性组件"的典型做法。摘要负责**语义**,关键事实负责**字节**。

## 十一、失败必须降级,而且要熔断

摘要是一次 LLM 调用,它会超时、会 429、会欠费。三条防线:

**第一,独立线程池 + 硬超时。** 不能和主对话共用一个池子,否则模型一慢,两边一起堵:

```java
Future<String> future = pool.submit(() -> { ... });
try {
    return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    throw new IllegalStateException("摘要调用超时(" + timeout.toSeconds() + "s)");
}
```

**第二,失败退化为本地规则摘要。** 至少保住三件事:用户最初的目标、关键标识符、最后一个诉求。

```java
sb.append("【用户目标】").append(clip(goal, 120)).append('\n');
sb.append("【已确认事实】").append(String.join(";", facts)).append('\n');
sb.append("【待办事项】").append(clip(lastAsk, 120)).append('\n');
sb.append("(注:本轮压缩时摘要模型不可用,以上为规则提取结果)\n");
```

**第三,连续失败就熔断。** 熔断期间直接走本地摘要,不再给慢模型拖死主链路的机会:

```java
private void onSummarizerFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= cfg.getBreakerFailures()) {
        breakerOpenUntil = System.currentTimeMillis() + cfg.getBreakerCooldown().toMillis();
        consecutiveFailures.set(0);
    }
}
```

对应到 SSE 事件里,前端能看到 `"degraded": true, "mode": "local-fallback"` —— 降级不是
静默发生的,运维能看见。

## 十二、归档召回:让被压掉的历史还能找回来

摘要是有损的。用户在第 5 轮说"我上周买的那个键盘按键失灵了",第 40 轮突然问"那个键盘的
订单号是多少" —— 摘要里可能只剩"用户在咨询键盘售后",订单号早被压没了。

这时唯一能救场的是**原始归档**。为什么不直接上向量检索:单会话归档上限 400 条消息、几十轮,
每次召回只扫几十个片段,BM25 级打分是微秒级的,还省掉一次 embedding 调用(那才是拖慢首字
延迟的元凶)。等要做跨会话、跨用户检索时再换向量库,接口不变。

打分 = 词项 IDF 加权命中 / 文档长度归一 + 时间衰减:

```java
for (String q : queryTerms) {
    if (!ts.contains(q)) continue;
    int docFreq = df.getOrDefault(q, 0);
    score += Math.log(1.0 + (double) n / (1 + docFreq));   // idf
}
score = score / Math.sqrt(Math.max(1, ts.size()));          // 长度归一
score += RECENCY_WEIGHT * ((double) (i + 1) / n);           // 越新权重越高
```

中文分词用**二元组**:不引入词典分词,召回率略高、精确率略低。对"找回相关片段"这个任务,
召回率更重要 —— 多给一段无关历史,模型自己会忽略;少给一段关键历史,就真丢了。

```java
if (run.size() <= 4) {
    out.add(wholeRun);          // 短句整句保留,避免"退款"被切成没意义的二元组
}
for (int i = 0; i + 1 < run.size(); i++) {
    out.add(bigram(run.get(i), run.get(i + 1)));
}
```

召回结果注入 system 时带一句"这些片段可能不完整,引用前先确认与当前问题一致",降低模型
对片段的过度信任。

## 十三、上下文分层:顺序不是随意的

```java
private String systemPrompt(Conversation conv, TurnSession session) {
    StringBuilder sb = new StringBuilder(props.getPrompt().getSystem());

    // 1. 工具清单(能力边界)
    toolRegistry.specs().forEach(s -> sb.append("- ").append(s.name())...);

    // 2. 长期记忆摘要 + 冲突裁决规则
    if (conv.hasSummary()) {
        sb.append("\n【长期记忆·会话摘要】\n").append(conv.getSummary())
          .append("(以上是更早轮次的压缩摘要;与最近消息冲突时,以最近消息为准)\n");
    }

    // 3. 关键事实(原文,不得改写)
    conv.getPinnedFacts().forEach(f -> sb.append("- ").append(f).append('\n'));

    // 4. 归档召回片段(标注可能不完整)
    sb.append(MemoryRecaller.render(session.recallHits()));
    return sb.toString();
}
```

顺序遵循两个经验:

- **模型对上下文末尾更敏感**,所以"最近的消息"永远在最后,摘要靠前;
- **摘要必须显式声明"冲突以最近消息为准"**。不给这句,模型经常把过时摘要当圣旨,
  用户明明改主意了它还在按老方案回答。

## 十四、把内部动作外发:前端看得见才可信

压缩、召回这些动作如果完全藏在后端,用户只会感觉"它有时候记得有时候不记得"。所以 SSE 里
新增三类事件:

```java
sink.emit("memory_compacted", Map.of(
        "mode", compaction.mode(),              // llm / local-fallback / local-breaker
        "degraded", compaction.degraded(),
        "beforeTokens", compaction.beforeTokens(),
        "afterTokens", compaction.afterTokens(),
        "foldedMessages", compaction.foldedMessages(),
        "summaryPreview", preview(compaction.summary(), 160)));

sink.emit("memory_recall", Map.of("hits", hits.stream().map(h -> Map.of(
        "startSeq", h.startSeq(), "endSeq", h.endSeq(),
        "score", h.score(), "text", preview(h.text(), 200))).toList()));
```

Vue 端收到就弹一条提示,并把右侧「记忆面板」刷成最新状态:

```ts
case 'memory_compacted': {
  showToast(`记忆压缩:折叠 ${e.data.foldedMessages} 条消息,${e.data.beforeTokens} → ${e.data.afterTokens} token`)
  break
}
case 'memory_recall':
  recallHits.value = (e.data.hits as RecallHit[]) ?? []
  break
```

React 端用的是同一套事件契约,只是状态放在 `useAgent` 这个 hook 里:

```ts
const handle = (e: StreamEvent) => {
  switch (e.name) {
    case 'delta': appendDelta(String(e.data.text ?? '')); break
    case 'tool_start': { /* 过渡语气泡若为空则摘掉,答案另起一个气泡排在卡片后面 */ }
    case 'memory_compacted': showToast(...); break
    ...
  }
}
```

两个前端的 UI 完全一致:左栏会话列表(带水位百分比与压缩次数)、中间对话(工具调用单独
渲染成卡片)、右栏记忆面板(水位条、摘要原文、关键事实 chips、归档召回打分)。

## 十五、实测:50 轮到底省了多少

`LongConversationTest` 用 mock 模型跑满 50 轮,预算故意设成 1200 token 以便快速触发压缩:

```
=== 50 轮压测结果 ===
累计消息数        : 100
当前窗口消息数    : 10
当前上下文 token  : 743(预算 1200)
历史峰值 token    : 870
压缩次数          : 15
归档消息数        : 90
估算省下 token    : 5068
摘要长度          : 199 字
关键事实          : [单号 A20261123001, 金额 899.00 元, 日期 2026-11-25]
```

100 条消息全量回放约 7400 token,压缩后稳定在 **743 token,省了 90%**,而且第 1 轮的订单号
在第 50 轮仍然可查。测试里的断言不只是"没报错":

```java
// 1. 预算:允许短时越线(到水位才压),但不能失控
assertThat(tokens).isLessThan((int) (props.getMemory().getBudgetTokens() * 1.35));

// 2. 序列合法:窗口首条必须是 user,tool 消息必须成对
assertThat(conv.getMessages().get(0).isUser()).isTrue();
assertValidPairing(conv.getMessages());

// 3. 关键信息不丢:摘要或关键事实里必须还能找到订单号
assertThat(conv.getSummary() + String.join(";", conv.getPinnedFacts()))
        .contains("A20261123001");
```

Redis 侧另有 7 个集成测试,覆盖序列化往返、Lua CAS、LTRIM 边界、TTL 单位、SCAN 计数。
没起 Redis 时用 `Assumptions` 自动跳过,不会让 CI 变红:

```java
@BeforeEach
void clean() {
    Assumptions.assumeTrue(available, "本机没有可用的 Redis,跳过集成测试");
    ...
}
```

```bash
mvn test                          # 35 个单测
REDIS_TEST_PORT=6390 mvn test     # 再加 7 个 Redis 集成测试
```

## 十六、踩坑清单

1. **`-parameters` 一定要开。** 不用 `spring-boot-starter-parent` 而只 import BOM 时,
   `@PathVariable` 拿不到参数名,运行时报 `Name for argument of type [String] not specified`。
   表现为"接口 500,但代码看起来完全正常"。
2. **Redis TTL 用 `PX` 不用 `EX`。** `EX` 只收整数秒,毫秒配置会被截断成 0,key 立刻过期。
3. **`KEYS` 是生产红线。** 统计会话数用 `SCAN`,不要用 `KEYS agent:*`,后者会阻塞整个实例。
4. **压缩后必须校验序列。** 别等下一次请求 400 才发现拆散了 tool 对,单测里直接断言。
5. **摘要失败不能抛到主链路。** 用户等的是回答,不是你的摘要任务。
6. **CAS 冲突不要合并历史。** 两个实例各写了一半,合并出来的序列几乎必然非法;宁可让用户重试。
7. **`delta_reset` 别忘了。** 模型先说"我查一下"再调工具,这段过渡语已经流给前端了,不撤回
   就会出现"我查一下……我查一下……"。
8. **mock 也要能扮演摘要模型。** 否则单测只能断言"没抛异常",断言不了"订单号还在"。

## 十七、怎么跑起来

```bash
git clone https://github.com/zhoubiao188/ai-agent-lab.git
cd ai-agent-lab/day02-memory-redis

# 1) 零依赖:内存存储 + mock 模型
mvn -q -DskipTests package
java -jar memory-service/target/memory-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar                 # :8080
cd web-vue && npm i && npm run dev                          # :5173

# 2) 切 Redis
docker compose up -d redis
java -jar memory-service/target/memory-service-1.0.0.jar --agent.memory.store=redis

# 3) 接真实模型
export AGENT_LLM_API_KEY=sk-xxx
java -jar memory-service/target/memory-service-1.0.0.jar \
     --agent.provider=openai \
     --agent.llm.base-url=https://api.deepseek.com/v1 \
     --agent.llm.model=deepseek-chat
```

打开 <http://localhost:5173>,连发 6~8 条消息,右侧面板就能看到水位回落、摘要生成、
关键事实 chips 和归档召回打分。

## 十八、下一篇

记忆解决的是"记得住",下一篇解决"答得准":**RAG 接入 + 召回评测集**。
我们会把知识库从 Day 01 的 BM25 换成向量检索,但重点不在"怎么调 embedding 接口",
而在**怎么证明它比 BM25 好** —— 建一个 50 条的标注集,量化 Recall@3 和 MRR,
把"感觉效果好"变成"数据说好 12 个点"。

代码仓库(每篇一个可独立运行的目录):
https://github.com/zhoubiao188/ai-agent-lab

如果这篇对你有用,点个赞让我知道这个系列值得继续写下去。有问题评论区见,尤其是你在生产上
遇到的记忆翻车现场 —— 那些比任何教程都值钱。
