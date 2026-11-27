线上出现过一次这样的退款:

```
已为你处理「退款」。订单 ORD-100234,实付 299.00 元。
退款单号 RF-230689,金额 0.00 元。
```

用户实付 299,退了 0 元,系统说"已为你处理"。

三块看板全是绿的:

- **耗时**:407ms,在 P95 以内;
- **错误率**:0,这条链路没有任何一个 5xx,没有任何一个 Span 状态是 ERROR;
- **token 成本**:137 token,比平时还便宜。

按"接口成功率"理解,这是一次完全成功的运行。按用户理解,这是一次把钱吞了的运行。

这就是 Day05 那套防线留下的最后一个洞:多 Agent 拆错会被 Critic 拦住,但 **Critic 只说"这次不算成功",它不说"是哪一步的哪个判断错了"**。而值班的人只有几分钟,不可能去读 29 个 Span。

所以这一篇做的东西很具体:**把一次运行里 29.8 个 Span、19 个决策,压成 3 张能解释自己的卡片**,并且用 200 条确定性故障注入证明它真的能找到根因。

完整代码在 [zhoubiao188/ai-agent-lab · day06-observability](https://github.com/zhoubiao188/ai-agent-lab/tree/main/day06-observability),`mvn test` 49 个用例全绿,两个前端都能跑。

---

## 一、Span 记录"做了什么",不记录"为什么这么选"

先说清楚为什么现成的 APM 不够用。

SkyWalking、Jaeger、Zipkin 这一套解决的问题是:请求从网关进来,经由几个服务,哪个环节慢、哪个环节报错。它们的抽象是 **Span = 一次远程调用**。

Agent 的运行不是这样的。它是一次**判断链**:

```
用户原话 ──▶ 意图是什么?   ──▶ 需要哪些参数? ──▶ 参数从哪来?
        ──▶ 用哪个工具?   ──▶ 步骤怎么排?     ──▶ 并发几个?
        ──▶ 结果够不够?   ──▶ 要不要补查?     ──▶ 什么算结束?
```

上面那个 0 元退款,坏在第 4 个环:用户说"能退多少退多少",没有给金额,于是参数绑定环节用**默认值 0 元**兜底。后面每一步都忠实执行了这个 0。

在传统 APM 里,这段信息不存在。Span `tool refund.preview` 只会告诉你:调用了、耗时 12ms、返回 200。它不会告诉你"你传进来的是一个本该问用户、却用默认值填上的 0"。

所以我给这套系统加了一层 **决策台账(Decision Ledger)**:每个"有替代方案的选择"都记一条,不是记结果,是记**权衡过程**。

### 20 个决策点

一次售后 Agent 的运行,我数出来 20 个这样的点,分成五组:

| 组 | 决策点 | 典型症状 |
|---|---|---|
| 理解 | D01 意图解析 / D02 槽位盘点 / D03 工具召回 / D04 工具选择 | 意图认错、槽位缺了却硬跑、正确工具没进候选 |
| 绑定 | **D05 参数来源绑定** / D06 参数纠偏 / D07 是否澄清 | 默认值悄悄生效、"一百二"解析成一万二 |
| 检索 | D08 检索范围 / D09 记忆召回 / D10 计划形态 / D11 步骤依赖 / D12 并发上限 / D13 预算分配 | 召回不足、依赖断开、并发过高打爆下游 |
| 执行 | D14 重试判断 / D15 降级选择 / D16 结果自检 / D17 是否补查 | 非幂等写被重试、该补查没补 |
| 收尾 | D18 幂等键 / D19 回复生成 / D20 结束条件 | 幂等键撞车、话术承诺了没做到的事 |

每个决策点都有 `code`、`title`、`question`、`symptom`、`weight()`。`weight` 是"这个点错了有多贵",D05 是 1.35(默认值生效后没人发现),D20 是 0.80(结束条件早一点晚一点问题不大)。

### 一条决策长什么样

```java
Decision.at(DecisionPoint.D05_ARG_BIND)
        .chosen("默认值兜底")            // 实际选了什么
        .basis(Basis.DEFAULT)            // 依据:规则/启发式/模型/记忆/默认值/降级
        .confidence(0.15)                // 这条判断自己有多确定
        .attr("slot.sources", bind.slots().sources())
        .alternative("全部来自原话", 1.0, "用户明确给出")
        .alternative("默认值兜底", 0.3, "缺失槽位用 0 填充")
        .alternative("追问用户", 0.9, "需要一次额外往返,自动模式下被跳过")
        .build();
```

四个字段是关键:**chosen + basis + confidence + alternatives**。

- 只有 `chosen` 是没用的 —— 它和普通日志一样,告诉你结果,不告诉你原因;
- 只有 `basis` 也不够 —— "依据默认值"这四个字,配不上一个具体金额;
- 有了 `alternatives`,决策才从"一条记录"变成"一次可复盘的取舍":**当时还能选什么,为什么没选**。

### margin:被否掉的选项离你有多近

有了候选列表,就能算一个比 confidence 更有信息量的量:

```java
public double margin() {
    // 候选里也包含"被选中"的那一项,算差距时必须先排除它,
    // 否则 max(全部候选) 恒等于 chosen 自己,差距永远是 0。
    double chosen = alternatives.stream()
            .filter(a -> a.name().equals(chosen)).mapToDouble(Alternative::score)
            .findFirst().orElse(1.0);
    double bestRejected = alternatives.stream()
            .filter(a -> !a.name().equals(chosen)).mapToDouble(Alternative::score)
            .max().orElse(0.0);
    // 用相对差距:不同决策点的分数量纲不同,绝对差没法统一设阈值
    return Math.max(0.0, Math.min(1.0, (chosen - bestRejected) / chosen));
}
```

`margin = 0` 的含义很重:**你选的这一项,并不比被你否掉的那一项更好。** 这不是"接近",这是"选错了"。

这段代码我第一版写错过:忘了排除 chosen 自己,于是 `max(全部候选)` 恒等于 chosen,所有决策的 margin 都是 0,全被标成"接近选错"——20 个决策点一起报警,等于没有报警。

---

## 二、瀑布图里的三个坑

第二屏是 Span 瀑布图:29 个 Span 按时间铺开,嫌疑的高亮,决策点画成时间点标记(它们耗时 < 0.5ms,画成条看不见)。

画这张图踩了三个坑,都不是"调样式"级别的。

### 坑一:并行步骤算 selfTime 会算出负数

`selfTime = 总耗时 - 子 Span 耗时`。这句话在串行链路里成立,在并行步骤下直接崩:

```
step 2 price.calc       |---------- 200ms ----------|
  tool price.calc       |---- 120ms ----|
  tool inventory.get        |---- 130ms ----|     ← 两个子 Span 重叠
```

子 Span 耗时之和 = 250ms,父 Span 只有 200ms,相减得 **-50ms**。

正解不是 max(0, x) 兜住,而是按**区间并集**合并:重叠的时间只扣一次。

```java
public long selfNanos() {
    long total = durationNanos();
    if (children.isEmpty()) return total;
    List<long[]> intervals = new ArrayList<>(children.size());
    for (Span c : children) {
        long s = Math.max(startNanos, c.startNanos);
        long e = Math.min(endNanos, c.endNanos);
        if (e > s) intervals.add(new long[]{s, e});
    }
    intervals.sort((a, b) -> Long.compare(a[0], b[0]));
    long covered = 0, curStart = -1, curEnd = -1;
    for (long[] iv : intervals) {
        if (curStart < 0) { curStart = iv[0]; curEnd = iv[1]; }
        else if (iv[0] <= curEnd) curEnd = Math.max(curEnd, iv[1]);
        else { covered += curEnd - curStart; curStart = iv[0]; curEnd = iv[1]; }
    }
    if (curStart >= 0) covered += curEnd - curStart;
    return Math.max(0L, total - covered);
}
```

### 坑二:子列表在跨线程挂载,普通 ArrayList 会抛并发修改异常

并行步骤的 Span 由另外几个线程创建,`parent.children().add(...)` 是在那些线程里发生的。`ArrayList` 在这里迟早会抛 `ConcurrentModificationException`,而且是那种"跑一万次才炸一次"的。

改成 `CopyOnWriteArrayList`:子 Span 只在结束时追加一次、遍历远多于写入,这个结构正好。

### 坑三:决策点画不出来

决策点耗时 0.02~0.08ms,占整次运行 407ms 的 **0.02%**。在瀑布图里就是一条比边框还细的竖线,肉眼看不见。

改成"耗时 < 0.5ms 就画成 3px 的时间点标记":

```html
:style="{ left: row.left + '%',
          width: row.span.durationMs < 0.5 ? '3px' : row.width + '%' }"
```

一个细节:`left` 是相对整次运行的偏移,不能用 `startMs / durationMs`,否则并行运行时长不同的两次运行,同一个位置会画到不同地方。

---

## 三、六信号嫌疑排序:把 29 个 Span 压成 3 个

第三屏是三张卡片。难点不是"排序",是**让排序能被质疑**。

如果只输出一个排名,值班的人要么信、要么不信,两种都没法复盘。所以每张卡片都带三样东西:**分数构成、可核对的证据、被否掉的选项**。

### 六个信号与它们的失效场景

| 信号 | 权重 | 回答什么 | 单独用会怎么错 |
|---|---|---|---|
| `selfMs` 自身耗时 | 0.20 | 慢在哪 | 漏掉所有沉默失败(它不慢) |
| `error` 错误 | 0.22 | 谁报错了 | 漏掉"返回 200 但结果是错的" |
| `retry` 重试放大 | 0.10 | 谁的代价被重复付了 | 只在有重试时有效 |
| `tokens` 成本 | 0.14 | 钱烧在哪 | 一次运行只有一次模型调用时恒满分 |
| `decision` 决策异常 | 0.16 | 哪个判断本身站不住 | 决策错但下游兜住了,就没信号 |
| `integrity` 完整性 | 0.18 | 有结果但结果是坏的 | 护栏报警会盖住真正的成因 |

六个信号里,只有 `integrity`(完整性)是这套系统特有的。

它的定义是:**接口成功、没有异常、但结果在业务上说不通**。比如退款写入返回 200,但回显金额是 0;比如试算接口返回"不允许",但 HTTP 状态是 200。

这类失败在传统监控里完全隐形 —— 这正是开头那笔 0 元退款的形态。

### 细节一:error 要自底向上衰减

最简单的做法是"Span 自己有 ERROR 记 1,没有记 0"。这样第一名永远是根节点 `run` —— 它下面有一个错误,于是整棵树都"有错"。

改成自底向上衰减:

```java
private void computeErr(Span span, Map<String, Double> out) {
    double maxChild = 0;
    for (Span child : span.children()) {
        computeErr(child, out);
        maxChild = Math.max(maxChild, out.getOrDefault(child.spanId(), 0.0));
    }
    double own = span.status() == SpanStatus.ERROR ? 1.0 : 0.0;
    out.put(span.spanId(), Math.max(own, 0.55 * maxChild));
}
```

衰减系数 0.55 有两个约束:大于 0.5,让"两步之外"的父节点仍能进候选;小于 1,保证越深的节点分越高 —— **真正的根因在叶子,不在根**。

### 细节二:selfShare 的分母不能用根的 selfTime

我第一版用 `span.selfMs() / root.selfMs()` 算耗时占比。跑出来所有 Span 的嫌疑分都是 **0.2**,一模一样。

原因:根节点的子 Span 把它的时间全占了,`root.selfMs()` ≈ 1ms。拿 0.02ms 除以 1ms,所有决策点都得到 1.0;拿 120ms 除以 1ms,被 clamp 成 1.0。于是"占比"这个信号对每个 Span 都饱和,排序完全失效。

分母必须是**整次运行的时长** `trace.totalMs()`。

### 细节三:token 信号要用固定预算归一化

同样的错误:`tokens / trace.totalTokens()`。只要一次运行只有一次模型调用,那个 Span 的占比恒等于 100%,永远拿满分。

改成对一个典型预算饱和:`min(1, tokens / 8000)`。这样 500 token 的答案和 12000 token 的答案才区分得开。

**这两个坑是同一个错误**:信号做归一化时,用了"这一批数据里的相对量",而排障需要的是"跨运行的绝对量"。

### 决策异常度:置信度只是入场券

```java
private double decisionAnomaly(Trace trace, Span span) {
    double confidence = ...;   // 决策自己报告的可信度
    double margin = ...;       // 与次优方案的相对差距
    double anomaly = 1.0 - confidence;
    if (margin <= 0.0001)      anomaly = Math.min(1.0, anomaly + 0.40); // 选错了,不是接近
    else if (margin < 0.1)     anomaly = Math.min(1.0, anomaly + 0.15); // 接近选错
    // 低置信度 + 本次运行确实有语义失败 → 这个决策几乎是"因"
    if (hasSemanticFailure(trace) && confidence < 0.6) anomaly += 0.15;
    return Math.min(1.0, anomaly * point.weight());
}
```

最后乘 `DecisionPoint.weight()` 很重要。同样是"置信度 0.15",错在 D05(参数绑定)和错在 D20(结束条件)的代价差一倍 —— 不乘权重的话,所有决策点平等,而实际上它们不平等。

---

## 四、200 条确定性故障注入:这套东西到底准不准

到这里有个必须回答的问题:**你说它能找到根因,凭什么?**

所以我做了一套评测:5 类故障 × 40 条 = 200 次运行,每次运行有一个**金标准根因**(埋点的时候就知道坏在哪),然后看嫌疑排序的第 1、第 3 名有没有命中它。

| 场景 | 植入的故障 | 金标准根因 |
|---|---|---|
| SLOW_TOOL | 订单查询超过单步预算 | `tool order.get` |
| RETRY_STORM | 库存接口连续两次 503 | `tool inventory.get` |
| SILENT_FAILURE | 退款写入返回 200 但回显 0 | `write refund.commit` |
| COST_BLOWUP | 知识库返回超大片段,prompt 膨胀 | `llm.answer` |
| ARG_DRIFT | 缺金额走默认值 0 元 | `decision D05` |

### 确定性从哪来

评测最怕"跑起来看情况"。两个东西保证可复现:

**虚拟时钟**:

```java
public void sleep(long millis) {
    if (millis > 0) nanos.addAndGet(millis * 1_000_000L);   // 只加数,不真睡
}
```

`Clock` 是个接口,生产用 `SystemClock`,评测用 `VirtualClock`。200 条样本原来要跑几分钟,现在 **400ms 出结果**,而且耗时分布完全确定 —— 判据里的阈值不会被机器负载影响。

**按 seed 派生故障**:`FaultPlan(seed, taskId)` 决定这一次哪一步注入什么故障。同 seed + 同任务 = 同一批故障序列,换台机器也一样。

### 结果

| 场景 | R@1 | R@3 | MRR | 平均名次 |
|---|---|---|---|---|
| SLOW_TOOL | 1.0 | 1.0 | 1.0 | 1.0 |
| RETRY_STORM | 1.0 | 1.0 | 1.0 | 1.0 |
| SILENT_FAILURE | 1.0 | 1.0 | 1.0 | 1.0 |
| COST_BLOWUP | 1.0 | 1.0 | 1.0 | 1.0 |
| ARG_DRIFT | 1.0 | 1.0 | 1.0 | 1.0 |
| **总体** | **1.0** | **1.0** | **1.0** | **1.0** |

200 条样本的平均规模:**29.8 个 Span、19.0 个决策、547ms**,其中沉默失败 80 条(40%),重试放大 1.44×。

三条基线,同样 200 条:

| 做法 | R@1 | 为什么不行 |
|---|---|---|
| 第一个报错的 Span | 0.40 | 80 条沉默失败一个都抓不到,剩下 120 条里还有一半错误位置不对 |
| 最慢的 Span | 0.20 | 慢 ≠ 错 |
| 最贵的 Span | 0.20 | 贵 ≠ 错 |

阅读量从 **29.8 个 Span 压到 3 张卡片**,下降 90%。

### 这个 1.0 要打折看

两件事必须先说清:

1. **权重是在这 200 条样本上调的,没有留出集。** 严格说这是"在这批故障上的拟合结果",不是泛化能力。
2. **五个剧本只覆盖"已知的坏法"。** 线上真实故障的分布远比这五种复杂,还有"两个故障叠加""故障在工具外部""用户输入本身就矛盾"这些没覆盖。

所以这组数字的正确读法是:**它度量信号设计是否覆盖了故障分类,不度量线上召回率。**

下面这段调参记录,比结果更重要。

---

## 五、从 0.40 到 1.00:三次修改

第一版跑出来是 **R@1 = 0.40**。比"第一个报错的 Span"那条基线(0.40)一点没强。这个结果当时挺打击人的 —— 六个信号、20 个决策点、291 行代码,和 `findFirst(ERROR)` 打平。

三次修改之后到 1.00。**每一次修改的原因都比代码本身有价值。**

### 修一:0.40 → 0.80,度量口径错了

第一版评测用 `spanId` 判定命中:金标准根因是 `refund.commit` 这个 Span 的 id,排序结果里出现同一个 id 就算命中。

问题:一次工具调用**重试 3 次会生成 3 个独立的 Span**(每次尝试单独计时,这样你才能看到"第 2 次尝试花了 900ms")。金标准记的是第 1 次尝试的 spanId,而排序第 1 名很可能是第 3 次尝试 —— **同一个工具、同一个故障、同一个结论,只是不是同一次尝试**。

这不是漏检,是我的度量口径错了。

```java
// 命中判定按"埋点所属的 Span 名"算,而不是按 spanId
String culpritName = spans.stream()
        .filter(s -> s.spanId().equals(result.culpritSpanId()))
        .map(Span::name).findFirst().orElse("");
```

改完 R@1 从 0.40 到 0.80。**这一改没有动一行排序代码,只是把评测标准改对了。**

这件事值得单独说:如果你在优化一个指标,先把指标本身的定义审一遍。我差点去调权重,而权重根本没问题。

### 修二:0.80 → 1.00,护栏不是现场

剩下 20% 全是 ARG_DRIFT 场景,而且排第一的永远是 `tool refund.preview`,根因 `decision D05` 排第二。

真实数据长这样:

```
0.200  tool refund.preview     TOOL     integrity: 0.18
0.160  D05 参数来源绑定         DECISION decision:  0.16
0.156  D06 参数纠偏             DECISION decision:  0.156
```

D05 的决策异常分已经打满了(1.0,再乘 D05 的权重 1.35 后被 clamp)。它输,不是因为它不够可疑,而是因为**完整性信号是一个 0/1 的二值信号,它的权重 0.18 比决策信号的 0.16 高**。

一开始我想直接把 decision 权重调到 0.18、integrity 降到 0.16。这能让指标变好,但那是**在测试集上调参数**,不是修问题。

真正的问题在这:两个完整性信号被我当成了同一种东西。

| 情形 | 例 | 坏结果落地了吗 | 事故现场在哪 |
|---|---|---|---|
| 写入回显与入参不一致 | 退 128 元,账务回显 0 元 | **落地了** | 就是这个 Span |
| 试算接口拒绝执行 | 预演算出金额 ≤ 0,拒绝 | **没落地** | 在生成参数的决策 |

试算接口是**护栏**,不是现场。它报出来的信息是"上游给我的东西不对";把它当第一嫌疑人,值班的人会去读一个证明不了因果的位置。

所以给完整性信号加了第二维:

```java
public enum IntegrityGrade {
    LANDED("已落地", 1.0),      // 坏结果已经落地,这个 Span 就是事故现场
    CAUGHT("护栏拦下", 0.5);    // 护栏报警,成因在上游决策
}
```

```java
double integrity = span.integrityOk() ? 0.0 : span.integrityGrade().severity();
```

改完 ARG_DRIFT 的 R@1 从 0 到 1.0,总体从 0.80 到 **1.00**。

顺带说一句:这个区分在**生产上比在评测里更值钱**。一次运行里如果只有 CAUGHT 级的失败、没有任何 ERROR,说明护栏工作正常、损失被挡住了 —— 这是好事,不该和"钱已经退错了"排在同一个严重级别。

### 修三:排序对了,证据没了

改完修二,数字好看了,我去前端看了一眼,发现一个更实际的问题:

`tool refund.preview` 掉出了前三。三张卡片变成了 D05 / D06 / D02,全是决策点,置信度 0.15、0.15、0.15,证据栏写的都是"与次优方案差距 0.50"。

**排序是对的,但清单没用了。**

因为一次运行里,`refund.preview` 是**唯一能肉眼核对的东西** —— 它上面写着"金额超出实付或为 0"。而 D05 的证据"置信度 0.15"只是告诉你"这个判断不太确定",它不能证明任何事。

排障清单的职责不只是"排出谁最可疑",还要保证**你能验证结论**。加一条覆盖规则:

```java
// 清单里没有完整性证据时,把最后一名换成它
if (!integrityIds.isEmpty() && top.stream().noneMatch(s -> integrityIds.contains(s.spanId()))) {
    Suspect evidence = all.stream().filter(s -> integrityIds.contains(s.spanId())).findFirst().orElse(null);
    if (evidence != null) {
        top.remove(top.size() - 1);
        top.add(evidence);
    }
}
```

现在这个 trace 的三屏长这样:

```
结论:失败。最可能的问题在 D05 参数来源绑定(决策),
依据:决策依据 默认值,置信度 0.15,与次优方案差距 0.50;
备选嫌疑:D06 参数纠偏、tool refund.preview。

--- 0.160  D05 参数来源绑定     [决策]
    · 决策依据 默认值,置信度 0.15,与次优方案差距 0.50
    · 被否掉的选项:[全部来自原话, 默认值兜底, 追问用户]

--- 0.156  D06 参数纠偏         [决策]
    · 决策依据 默认值,置信度 0.30,与次优方案差距 1.00

--- 0.112  tool refund.preview  [工具]
    · 护栏拦下:业务试算未通过:金额超出实付或为 0
    · 这里是护栏,不是现场:坏结果没落地,成因在上游决策
```

第一张告诉你"因",第三张告诉你"凭什么能看见"。这才是一份能拿去复盘的清单。

---

## 六、沉默失败:80 条里最该被监控的那一半

200 条样本里,80 条(40%)是**沉默失败**:没有 ERROR、没有超时、没有 5xx,但结果是错的。

它们的共同特征是"接口成功、语义失败":

| 情形 | HTTP | 结果 |
|---|---|---|
| 退款写入回显 0 元 | 200 | 钱没退,单号有了 |
| 试算拒绝但流程继续 | 200 | 预演说不行,还是提交了 |
| 答案里承诺了没做到的字段 | 200 | "已为您加急",但没建工单 |
| 自检发现证据不足 | 200 | 硬着头皮回答 |

这类失败靠错误率永远发现不了。所以指标里单独有一个 `silentRate`:

```java
boolean silent = trace.outcome() == Outcome.SUCCESS
        && spans.stream().anyMatch(s -> !s.integrityOk());
```

反过来我还加了个"可信度" `trust`:这次运行自己给自己的打分,由自检失败项、证据条数、降级次数决定。`trust < 0.6` 的运行即使成功也全部保留 —— 它们极可能是下一个事故。

---

## 七、成本口径:三个被算错的指标

成本这块我一开始全算错了,列出来供参考。

| 指标 | 错误算法 | 为什么错 | 正确算法 |
|---|---|---|---|
| 单次成功成本 | 总成本 / 总运行数 | 奖励"少干活":跑得越少越便宜 | 总成本 / **成功次数** |
| 重试放大 | Span 总数 / 首次尝试数 | 并行步骤被算成放大 | **尝试 Span 数** / 首次尝试 Span 数 |
| 沉默失败率 | 无 ERROR 运行数 / 总数 | 把"正常成功"也算进去了 | 无 ERROR **但完整性失败** / 总数 |

重试放大那个尤其典型。一次运行 5 个步骤并行,Span 总数天然就比步骤数多,拿它当分子,系数永远大于 1。真正的"放大"是**同一次调用被重复执行**:

```java
long invocations = attempts.stream().filter(s -> s.attempts() <= 1).count();
return round(attempts.size() / (double) invocations);   // 1.44 倍 = 平均每次调用重试 0.44 次
```

---

## 八、线上要能落地:采样、脱敏、导出

前面都是"怎么排障",这一节是"怎么不把成本搞爆"。

### 尾部采样

头部采样(接到请求就掷骰子)在这类系统里非常坑:90% 的成功请求留下来了,那 10% 出错的却被扔了 —— 而排障需要的恰恰是错的那些。

尾部采样等运行结束、拿到结论再决定:

```java
public boolean mustKeep(Trace trace) {
    if (trace.outcome() != Outcome.SUCCESS) return true;      // 失败必留
    if (trace.trust() < trustFloor) return true;              // 低可信度必留
    for (Span span : trace.spans()) {
        if (span.status() == SpanStatus.ERROR || !span.integrityOk()) return true;
    }
    return false;
}
```

其余成功请求按 traceId 哈希采样 —— **用 traceId 自己哈希,而不是 `Math.random()`**:同一个 trace 反复评估结果一致,回溯时不会"刚才还在,刷新就没了"。

这里还踩了一个坑:采样规则曾经把刚跑完的 trace 判为不采,前端刷新就 404。修法是把两件事分开 —— **采样只决定"要不要上报",不决定"要不要存"**。本地存储全量保留(环形缓冲),上报按采样走。

### 脱敏

Agent 的 trace 里天然有手机号、身份证、银行卡。脱敏必须在**写入 Span 属性之前**做,不能等导出时再脱 —— 否则内存里、日志里、导出文件里各留一份。

```java
// 手机号 / 身份证 / 银行卡 / 邮箱 / apiKey 五类规则
span.attr("user.contact", redactor.redact(raw));
span.attr("user.contact.fp", redactor.fingerprint(raw));   // SHA-256 前 8 位
```

保留 `fingerprint` 是为了"同一个用户在不同 trace 里能不能对上" —— 脱敏不能把关联能力一起脱掉。

### 导出

`GET /api/runs/{traceId}/export` 输出 ndjson:一行一个 Span、一行一个决策。可以直接进 Loki、ClickHouse、ES。

```json
{"kind":"span","traceId":"43a87c...","spanId":"6db897...","name":"tool refund.preview","durationMs":12.4,"integrity":false,"integrity.grade":"CAUGHT","attrs":{"integrity.reason":"业务试算未通过:金额超出实付或为 0"}}
{"kind":"decision","traceId":"43a87c...","code":"D05","chosen":"默认值兜底","basis":"DEFAULT","confidence":0.15,"margin":0.50}
```

用 ndjson 而不是一个大 JSON 数组,是因为它**可以流式写、可以 `tail -f`、可以被日志管道直接切分**。

---

## 九、这套东西的边界

写完得说清它不解决什么,不然容易变成"又一个万能看板"。

**1. 它不理解业务。** D05 的置信度 0.15 是一个阈值判断的产物,不是"我知道这个金额错了"。它能告诉你"这里最可疑",不能告诉你"应该退 278"。

**2. 20 个决策点是手写的,不是自动发现的。** 换一个业务域,这套点表要重写。我现在倾向于按"有没有替代方案"来枚举,而不是按代码结构 —— 有替代方案的地方才有判断,有判断的地方才值得记。

**3. 权重是在 200 条样本上调的。** 没有留出集,这是明确的方法论缺陷。下一步应该做成两套:一套调参、一套报数,并且把调参过程本身固化成测试。

**4. 它假设埋点是全的。** 漏埋一个决策点,排序就永远找不到那一类根因 —— 而且你不会知道。这也是我为什么坚持把"20 个决策点"做成枚举:枚举能保证 `values().length == 20` 被测试固定住,漏了会挂。

如果要继续做,我会按这个顺序:

- **多故障叠加**:现在每条样本只有一个故障,真实事故常常是两个同时发生;
- **跨 trace 的因果**:现在只看单次运行,而"这个工具今天一直坏"要靠聚合;
- **把排障结论回灌**:人工确认的根因应该反馈成权重,而不是我拍脑袋调。

---

## 十、跑起来

```bash
git clone https://github.com/zhoubiao188/ai-agent-lab
cd ai-agent-lab/day06-observability

mvn -q -DskipTests package

java -jar agent-service/target/agent-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar               # :8080
cd web-vue   && npm i && npm run dev                     # :5173(Vue)
cd web-react && npm i && npm run dev                     # :5174(React)
```

试试这条 0 元退款:

```bash
curl -X POST localhost:8081/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"question":"A10003 我不想要了,能退多少退多少","scenario":"ARG_DRIFT"}'
```

跑评测(200 条,秒级,结果可复现):

```bash
curl -X POST localhost:8081/api/bench/triage
```

接口清单:

| 接口 | 用途 |
|---|---|
| `POST /api/runs` | 跑一次,响应头带 `X-Trace-Id` |
| `GET /api/runs` | 最近运行列表(含第一嫌疑人) |
| `GET /api/runs/{traceId}` | 详情:Span 树 + 决策台账 + 3 张嫌疑卡片 |
| `GET /api/runs/{traceId}/export` | ndjson 导出 |
| `POST /api/bench/triage` | 200 条故障注入评测 |
| `GET /api/metrics/overview` | 成本与成功率口径 |
| `GET /api/meta/decisions` | 20 个决策点定义 |

技术栈:Java 17 · Spring Boot 3.3 · Spring Cloud Gateway 2023 · Micrometer · Vue 3.5 · React 18 · Vite 6。

**49 个测试**,其中有一组专门测"排序为什么这么排":护栏拦下时必须让位给上游决策、证据覆盖规则必须生效、并行 Span 的 selfTime 不能为负。

---

## 一点感想

这一篇最花时间的不是代码,是**改自己的度量**。

三次修改里,只有第二次动了算法(还只是加了一个维度),第一次和第三次都在改"我判断它对不对的标准"。R@1 从 0.40 到 1.00 的过程,更像是一次对自己的 code review,而不是一次调优。

如果你也在做 Agent 的可观测,我的建议只有一句:

> 别急着画看板。先想清楚"一次运行里有哪些判断是有替代方案的",把它们记下来。
> 没有决策记录的 trace,只能告诉你系统做了什么,永远告诉不了你为什么这么做。

系列其他几篇:

- Day 01 从零手写支持 Function Calling 与 SSE 的 Agent 服务
- Day 02 Redis 会话记忆 + 滚动摘要压缩,支撑 50 轮长对话
- Day 03 RAG 混合召回 + 特征重排 + 50 条评测集
- Day 04 工具调用可靠性:参数自纠正 / 超时重试 / 熔断降级
- Day 05 多 Agent:计划契约、意图覆盖、Critic 复核与重规划

代码仓库:[github.com/zhoubiao188/ai-agent-lab](https://github.com/zhoubiao188/ai-agent-lab)
