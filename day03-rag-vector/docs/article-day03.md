# RAG 答不准,到底该改哪一步?我用 50 条评测集把四种检索策略跑成了对照实验

做 RAG 最容易踩的坑,不是接不上模型,而是**接上之后不知道自己在改什么**。

一个典型的 RAG 上线过程是这样的:文档切 500 字 → 灌进向量库 → top3 拼进 prompt → 模型回答。
本地测十个问题,对了八个,感觉可以了。上线一周,用户开始反馈"答得不对"。

这时候你面对的是一个黑盒:可能是分块把关键句切断了,可能是向量模型不认识这个术语,可能是 top-k 太小漏了正确块,
也可能是资料里根本没有答案、模型在编。**四种原因的表现完全一样——答案不对。** 于是你开始凭感觉调参:
块长从 500 改到 800,top-k 从 3 改到 5,换个向量模型,加一段"请严格依据资料"的提示词。改完感觉好了一点,但说不清是哪一步起的作用,
更不敢确定有没有把原来对的问题改坏。

这篇的做法很朴素:**先把评测集建起来,再把四种检索策略做成可以并排对比的开关,让每一次改动都有一个数字告诉你方向。**

项目已开源(Spring Boot 3 + Spring Cloud Gateway + Vue3 / React 双前端),地址在文末。

---

## 一、先定指标,再谈优化

没有指标的 RAG 调优就是玄学。我用了四个指标,它们回答的是不同的问题:

- **Recall@k**:正确块有没有进前 k。这是**上游能力**,k 再大也救不回没召回的东西;
- **MRR**:第一个正确结果排在第几,`1/rank` 取平均。衡量"答案是不是在最前面";
- **NDCG@5**:考虑位置的排序质量,给靠前命中更高权重;
- **P50 / P95 延迟**:效果涨了但延迟翻十倍,这个改动在线上的价值就是负的。

Recall 和 MRR 的区别值得强调:**Recall@5 高而 MRR 低,说明东西召回了但排得靠后**——这时候该动的是融合与重排;
**Recall@5 本身就低,说明根本召回不到**——这时候去调重排是浪费时间,得回去看分块和 embedding。

指标实现里有一个坑我踩过,也是最常见的实现错误:NDCG 的增益必须"一个标注只能被消耗一次"。
同一篇文档有两块都被召回时,如果不去重,增益会被重复累加,NDCG 能算出大于 1 的荒谬值。

```java
public static double ndcgAtK(List<Chunk> retrieved, List<Gold> golds, int k) {
    boolean[] consumed = new boolean[golds.size()];
    int depth = Math.min(k, retrieved.size());
    double dcg = 0;
    for (int i = 0; i < depth; i++) {
        int matched = matchUnused(retrieved.get(i), golds, consumed);
        if (matched >= 0) {
            consumed[matched] = true;      // 同一个标注只计一次
            dcg += 1.0 / log2(i + 2);
        }
    }
    double idcg = 0;
    for (int i = 0; i < Math.min(golds.size(), k); i++) {
        idcg += 1.0 / log2(i + 2);
    }
    return idcg <= 0 ? 0 : dcg / idcg;
}
```

标注方式上,我没有标"哪一块是答案",而是标 **(docId, anchor)**:
文档 ID 加上一段特征字符串(比如 `singletonFactories`)。只要召回的块里包含这个字符串就算命中。
这样做的好处是**改分块参数不需要重标评测集**——块边界会变,但"这段话在哪篇文档里"不会变。
评测集共 50 条,按查询类型分了五类:`terminology`(术语)、`paraphrase`(换个说法问)、`code`(配置/代码)、
`english`(中英混问)、`multi-hop`(需要跨两篇文档)。

---

## 二、分块:代码块必须当原子,重叠必须在句子边界

分块是 RAG 里最不性感、但对效果影响最大的一步。我的切分顺序是三层:

**1、先按 `##` 切小节。** 技术文档的小节天然是一个语义单元,先把这层结构用上,比无脑按字数切强得多。
小节标题还会跟着块一起走,后面重排时"标题命中"是一个很强的信号。

**2、小节内按句子打包到 520 字,块间留 90 字重叠。** 重叠是为了兜住被切断的跨块信息。

**3、代码围栏整块当原子,绝不允许切。** 半个代码块比没有代码块更糟——模型会拿半段 YAML 当完整配置抄给你。

这里有个 bug 我印象很深:第一版 overlap 实测是 **0**,查了半天发现原因是"先把多句攒成一大块再切"。
硬切的位置落在句子中间,而重叠区是以"原子"为单位回退的,攒成大块之后一个原子的长度就超过了 90 字预算,
于是回退循环一次都没进去。**修正只有一句话:必须一句一个原子。**

```java
// 回退若干句作为重叠区,至少保留一个原子,否则会原地打转
int back = i;
int overlap = 0;
while (back > start + 1 && overlap + atoms.get(back - 1).length() <= cfg.getOverlapChars()) {
    overlap += atoms.get(back - 1).length();
    back--;
}
// 单句就超过 overlap 预算时,至少重叠一句,否则两块之间会出现信息断层
if (back == i && i > start + 1) {
    back = i - 1;
}
```

还有一个细节:末尾碎片并入前一块。否则你会得到一堆"只有半句话"的块,这种块在向量空间里几乎随机,
既占 top-k 名额又贡献不了信息。

分块参数:520 字 / 90 字重叠 / 最短 140 字。**改这个值必须全量重建索引**——所以它不适合频繁调,
应该先用评测集确认方向,再一次性定下来。

---

## 三、双路召回:BM25 在技术文档里被严重低估

很多人做 RAG 的第一反应是"向量库 + embedding",直接把关键词检索扔了。这是个损失。

看实测数据:单看 MRR,**向量召回 0.913 输给了 BM25 的 0.953**。原因很直白——技术文档的查询里,
大量是术语、配置项、类名、错误码(`lb://`、`server.shutdown=graceful`、`singletonFactories`),
这类查询需要的是**精确匹配**,而不是语义泛化。向量模型反而会把"三级缓存"和"缓存穿透"拉到一起。

反过来,向量在 `paraphrase` 类型上是 1.00,BM25 只有 0.92:"应用启动很慢,时间都花在哪一步了" 这种问法里
没有一个词出现在原文的"refreshContext"旁边。**两路的能力是互补的,不是替代关系。**

BM25 我按标准公式实现,idf 用恒正变体,避免高频词出现负分:

```java
// idf = log(1 + (N - df + 0.5) / (df + 0.5))
// score = Σ idf · tf·(k1+1) / (tf + k1·(1 - b + b·dl/avgdl)),k1=1.2, b=0.75
for (String term : Tokenizer.distinct(query)) {
    List<int[]> plist = postings.get(term);
    if (plist == null) continue;           // 未登录词直接跳过,不做平滑
    double idf = idf(term);
    for (int[] p : plist) {
        int doc = p[0], tf = p[1];
        double dl = docLen[doc];
        acc[doc] += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgDocLen));
    }
}
```

中文分词没有引第三方库,用的是**二元组(bigram)**:连续汉字两两成词,遇到停用字整组丢弃。
英文按词切,同时把 `spring.data.redis` 这类带分隔符的复合词拆成整体加各部分,也做驼峰拆解。
理由是:**BM25 的分词必须和向量模型的分词解耦**。用 jieba 之类的分词器,一次分词错误就永久丢一个召回路径,
而且很难解释"为什么这条查不到";bigram 的行为完全可预测,查不到的时候你一眼能看出是哪个词没对上。

向量侧给了两个索引实现。`FlatVectorIndex` 是暴力点积,召回 100%,当基线;
`IvfVectorIndex` 用 k-means 聚成 nlist 个簇,检索只扫最近的 nprobe 个簇:

```java
// 搜索量从 n 降到 n·nprobe/nlist,代价是召回率不再是 100%
Arrays.sort(order, (a, b) -> Double.compare(
        FlatVectorIndex.dot(query, centroids[b]),
        FlatVectorIndex.dot(query, centroids[a])));
for (int p = 0; p < Math.min(nprobe, centroids.length); p++) {
    for (int idx : lists.get(order[p])) {
        // 只在被探测到的簇里做点积
    }
}
```

写出 IVF 的意义不在于替代 Milvus,而在于你能亲手看到**近似索引的召回损失从哪来**。
线上排查"某条数据永远搜不到",第一个要怀疑的就是它落在一个没被探测的簇里。nprobe 就是"速度 ↔ 召回"的旋钮。

---

## 四、融合:别加权求和,用 RRF

两路结果怎么合?最常见的写法是归一化后加权:`0.5 * bm25_norm + 0.5 * vec_norm`。
这个方案在生产上很难稳,原因是**归一化依赖当次结果的分布**:BM25 分数无上界(这次最高 20.7,下次可能 3.1),
余弦相似度挤在 0.2~0.5 这个窄区间里。用 min-max 归一化,分母来自这一次的召回集,换个查询尺度就漂,
调好的权重下周就失效。

RRF(Reciprocal Rank Fusion)绕开了分数,只用名次:

```java
private List<Scored> rrf(List<Scored> lexical, List<Scored> semantic) {
    Map<String, Double> fused = new LinkedHashMap<>();
    for (int i = 0; i < lexical.size(); i++) {
        fused.merge(lexical.get(i).chunkId(), cfg.getBm25Weight() / (cfg.getRrfK() + i + 1), Double::sum);
    }
    for (int i = 0; i < semantic.size(); i++) {
        fused.merge(semantic.get(i).chunkId(), cfg.getVectorWeight() / (cfg.getRrfK() + i + 1), Double::sum);
    }
    List<Scored> out = new ArrayList<>(fused.size());
    fused.forEach((id, s) -> out.add(new Scored(id, s)));
    out.sort((a, b) -> Double.compare(b.score(), a.score()));
    return out;
}
```

`k` 取 60(原论文的经验值),它对量纲完全不敏感,也不需要任何标定。
换 embedding 模型、换语料、换语言,这个融合层都不用动——这是它比加权求和值钱的地方。

实测:融合把 R@5 从 0.960/0.970 提到 **0.980**,MRR 从 0.913 提到 0.950,
而且 `multi-hop` 类型的 R@5 从 0.67(BM25) / 0.83 直接到 **1.00**。跨文档的问题正是"单路都不够、互补才有效"的典型。

---

## 五、重排:五个可解释特征,α 必须靠评测集标定

最后一层是重排。这里我没上 Cross-Encoder,原因有两个:一是需要额外模型推理,几十到几百毫秒;
二是**调优时你只能看到"分数变了",不知道原因**。线上出问题,你没法回答"是哪一项把正确块压下去了"。

所以我用了五个纯 CPU、微秒级、每项都能单独打印的特征:

1. **IDF 加权覆盖率**(权重 0.42):查询词有多少被块覆盖,稀缺词权重更高;
2. **连续短语命中**(0.20):相邻两词在块中也相邻,信号远强于分散命中;
3. **小节标题命中**(0.12):标题命中通常意味着整块都在讲这件事;
4. **块长偏好**(0.10):太短缺上下文,太长主题被平均,200~620 字最舒服;
5. **命中位置**(0.16):越靠前的命中越可能是这一块的主题。

最终分是 `α · 特征分 + (1-α) · 归一化融合分`。保留一部分融合分是刻意的——纯特征分会丢掉
"两路都排第一"这种一致性信号。

α 是唯一需要标定的参数,而且**必须标定**。我写了个扫描测试,从 0 到 1 跑一遍:

| α | 0.0 | 0.2 | 0.4 | 0.5 | 0.6 | 0.7 | 1.0 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MRR | 0.950 | **0.959** | 0.929 | 0.929 | 0.922 | 0.922 | 0.910 |
| NDCG@5 | 0.953 | **0.961** | 0.938 | 0.938 | 0.932 | 0.933 | 0.927 |

这张表信息量很大:**α 不是越大越好,0.7 会把 MRR 从 0.959 拖到 0.922**。凭直觉给 0.7(觉得"特征更可靠就该多信特征")
是很自然的错误,而它需要一次扫描才能被发现。最终取 0.2。

重排前还有一个必须做的截断:候选只取前 `max(k*3, 12)` 个。重排代价是 O(查询词数 × 块长 × 候选数),
把 30 个候选全重排,收益递减而成本线性上涨。

---

## 六、消融实验:三条结论,一条比一条反直觉

同一批 50 条样本、同一套语料,只换检索策略:

| 策略 | R@1 | R@3 | R@5 | MRR | NDCG@5 | P95 |
| --- | --- | --- | --- | --- | --- | --- |
| ① 关键词 BM25 | 0.900 | 0.960 | 0.970 | 0.953 | 0.949 | 101μs |
| ② 向量召回 | 0.860 | 0.910 | 0.960 | 0.913 | 0.919 | 86μs |
| ③ 混合 RRF | 0.900 | 0.960 | **0.980** | 0.950 | 0.953 | 84μs |
| ④ 混合 + 重排 | **0.920** | **0.970** | **0.980** | **0.959** | **0.961** | 437μs |

按查询类型拆解 R@5,互补关系一目了然:

| 策略 | terminology | paraphrase | code | english | multi-hop |
| --- | --- | --- | --- | --- | --- |
| ① BM25 | 1.00 | 0.92 | 1.00 | 1.00 | 0.83 |
| ② 向量 | 1.00 | **1.00** | 1.00 | 0.50 | 0.67 |
| ③ 混合 RRF | 1.00 | 1.00 | 1.00 | 0.50 | **1.00** |
| ④ 混合+重排 | 1.00 | 1.00 | 1.00 | 0.50 | 1.00 |

**结论一:纯向量召回不一定赢。** 在这批技术文档上,BM25 的 MRR 反而高 0.04。如果你的语料是 API 文档、
运维手册、代码库,只上向量是自断一臂。

**结论二:融合的收益来自"补短板",不是"整体更强"。** `paraphrase` 上向量补了 BM25,`multi-hop` 上融合把 0.67/0.83 拉到 1.00。
看均值只能看到 +0.02,看类型拆解才能看到 +0.33。

**结论三:重排的性价比要算清楚。** 它只值 **+0.009 MRR**,代价是延迟从 84μs 涨到 437μs(约 5 倍)。
在这个数据规模下值得,因为 437μs 仍然是零头;但如果你的链路里有 200ms 的 LLM 调用,重排的 0.35ms 根本不算成本,
**真正该担心的是它没效果却让你以为调好了**。

还有一个失败样本值得看:`q17 · english · "lb:// 前缀在网关路由里是什么意思"`,FULL 排第 8,刚好卡在 Top-5 外面。
原因是我用的本地 embedding 是字符 n-gram 哈希,它**不理解语义**,`lb://` 这种符号几乎不产生有效特征。
这不是融合或重排能救的,只能换语义向量模型。**这类"模型能力不足"的失败,必须和"排序不好"的失败区分开**,
否则你会一直在错的地方优化。

---

## 七、生成:先推引用,再推正文

检索对了,生成环节还有两个直接影响体验的点。

第一是**提示词必须允许说"不知道"**。不写这一条,模型会用先验知识把空缺补上,答得流畅但全是幻觉:

```java
public static final String SYSTEM = """
        你是一个严谨的技术文档助手。
        规则:
        1、只依据给定的资料片段回答,不要引入资料之外的事实;
        2、如果资料不足以回答,直接说"资料中没有相关内容",并说明缺少什么;
        3、每个结论后面用 [编号] 标注来源,编号对应资料片段;
        4、回答用中文,先给结论再给依据,不要复述问题。
        """;
```

第二是 **SSE 的事件顺序**:先推 `citations`(引用片段),再逐个推 `delta`(正文增量)。
这个顺序不是随便定的——引用一到位,前端就能显示"正在查阅《Spring Cloud Gateway 实战 · SSE 流式输出被网关缓冲》",
用户的等待感直接减半。等正文全生成完再一起返回,同样是 3 秒,体验差一个档次。

```java
// 检索完成后立刻推引用,不等 LLM 第一个 token
send(emitter, closed, "citations", citations);

StringBuilder answer = new StringBuilder();
llm.streamAnswer(req.query(), contexts, delta -> {
    if (closed.get()) return;         // 客户端已断开,停止生成,省 token
    answer.append(delta);
    send(emitter, closed, "delta", Map.of("text", delta));
});
send(emitter, closed, "done", Map.of("answer", answer.toString(),
        "retrieveMs", retrieveMs, "totalMs", System.currentTimeMillis() - t0, "model", llm.name()));
```

`closed` 这个标志位很关键。用户关掉页面后,后端如果还在傻乎乎地生成,既浪费钱又占线程。
`onCompletion` / `onTimeout` / `onError` 三个回调都要挂上,任何一个都能标记断开。

---

## 八、网关:两个必踩的 SSE 坑

SSE 走 Spring Cloud Gateway,有两处配置不注意,表现都是"前端一句话都不出,最后一次性全吐出来":

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        # 坑一:默认响应超时对 SSE 太短,后端还在生成,网关先 504
        response-timeout: 300s
        connect-timeout: 5000
```

第二个坑在响应头。某些 Nginx 和浏览器会把 `text/event-stream` 缓存住,必须显式关掉:

```java
if (isEventStream(request)) {
    response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
    response.getHeaders().set("X-Accel-Buffering", "no");   // 关掉 Nginx 的响应缓冲
}
```

顺手还在网关做了全链路 `X-Request-Id`:入口生成、透传下游、写回响应头。
排查"用户说慢"的时候,拿着这个 ID 就能把网关日志和后端日志串起来,不用靠时间戳猜。

---

## 九、前端:EventSource 用不了,得自己解 SSE

这是很多人第一次写 AI 前端会卡住的地方。浏览器原生的 `EventSource` **只支持 GET**,
而问答接口需要 POST 一个 JSON body。所以只能用 `fetch` 拿 `ReadableStream`,自己解析事件帧:

```ts
const reader = res.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
for (;;) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  let idx = buffer.indexOf('\n\n')
  while (idx >= 0) {
    handle(buffer.slice(0, idx))     // 按空行切帧
    buffer = buffer.slice(idx + 2)
    idx = buffer.indexOf('\n\n')
  }
}
```

三个容易出错的地方:必须用 `{ stream: true }` 解码(否则一个 UTF-8 汉字被切在两个 chunk 之间会变成乱码);
必须缓存 `buffer`(一帧可能跨多个 chunk);单帧 JSON 解析失败要吞掉,不能影响后续帧。

Vue 和 React 两套前端我都写了,逻辑完全一样,只有状态管理方式不同。React 这边有个必须注意的点:

```tsx
// 必须用函数式更新:同一帧内多个 delta 会读到同一个旧 answer 而相互覆盖
onDelta: (text) => setAnswer((prev) => prev + text)
```

写成 `setAnswer(answer + text)` 在快速流式下会丢字——因为闭包捕获的是渲染时的旧值。这个 bug 很难复现,
表现是"偶尔少几个字",但用户会觉得模型在胡说。

---

## 十、怎么跑起来 / 怎么接真实模型

零配置就能跑:向量用本地字符 n-gram 哈希(feature hashing),LLM 用 mock,不需要任何 API Key。
分块、BM25、融合、重排、评测全链路都是真的,数字也是真的。

```bash
mvn -q package -DskipTests
java -jar rag-service/target/rag-service-1.0.0.jar &   # 8081
java -jar gateway/target/gateway-1.0.0.jar &           # 8080

cd web-vue && npm i && npm run dev                     # 5173
cd web-react && npm i && npm run dev                   # 5174
```

接真实模型只换环境变量,代码一行不改:

```bash
export RAG_EMBED_PROVIDER=openai RAG_EMBED_MODEL=text-embedding-3-small \
       RAG_EMBED_BASE_URL=https://api.openai.com/v1 RAG_EMBED_API_KEY=sk-xxx \
       RAG_LLM_PROVIDER=openai RAG_LLM_MODEL=deepseek-chat \
       RAG_LLM_BASE_URL=https://api.deepseek.com/v1 RAG_LLM_API_KEY=sk-xxx
```

之所以能做到"只换开关",是因为 `EmbeddingModel` 只有 `dim()` / `name()` / `embed()` 三个方法,
`LlmClient` 只有 `streamAnswer()`。**接口窄,替换成本才低。** 另外 embedding 外面套了一层 LRU 缓存装饰器,
评测时同一批查询反复跑,命中缓存后向量化耗时为 0——这也是评测延迟数据要标注"命中缓存"的原因,
不然很容易把自己的缓存当成模型变快了。

---

## 写在最后

这个项目里最值钱的不是 RRF 或重排的代码,而是那张消融实验表。它让我在三个地方改变了原来的判断:

- 我本以为向量一定比 BM25 强,数据说不是;
- 我本以为 α 越大越信特征越好,扫描说 0.2 最优、0.7 反而掉分;
- 我本以为重排能带来明显提升,实测只有 +0.009 MRR。

**做 AI 应用和做普通后端最大的区别就在这里:普通后端的正确性可以靠单测钉死,AI 应用的效果只能靠评测集量化。**
没有评测集的 AI 项目,每一次"优化"都是在赌。

下一篇写**工具调用的可靠性**:并行调用、超时与重试、参数校验失败后如何让模型自我纠正,
以及怎么用故障注入测试把 Agent 的失败路径跑出来。

代码:https://github.com/zhoubiao188/ai-agent-lab (目录 `day03-rag-vector`)
系列前两篇:Day01 从零手写 Agent 核心 / Day02 Redis 会话记忆与滚动摘要
