# Day 06 · 可观测与成本:一次 Agent 运行 30 个 Span,谁先看

Day05 用四层防线把任务成功率从 50% 拉到 100%。这一篇解决它留下的最后一件事:

**线上真的出问题时,谁能在一分钟里说出问题在哪。**

一次 Agent 运行平均产生 **29.8 个 Span、19 个决策**。失败的时候把 raw trace 甩给值班的人,
等于什么都没给。这一篇做的就是那层"先替人看一遍"的东西:20 个决策点台账、Span 瀑布图、
六信号嫌疑排序、尾部采样、脱敏、JSONL 导出,以及一套 **200 条确定性故障注入**的评测。

## 三屏定位法

```
① 最近运行        一次运行一行:结论、耗时、token、沉默失败标记、第一嫌疑人
        │
        ▼
② 瀑布图          29 个 Span 按时间铺开,嫌疑 Span 高亮,决策点画成时间点标记
        │
        ▼
③ 凭什么           3 张嫌疑卡片:分数构成(六个信号各贡献多少)+ 可核对的证据 + 被否掉的选项
```

第 ③ 屏是重点。排名不解释自己,值班的人就只能相信算法;而复盘必须能被质疑。

## 六信号嫌疑排序

| 信号 | 权重 | 它回答的问题 | 单独用会怎么错 |
|---|---|---|---|
| `selfMs` 自身耗时 | 0.20 | 慢在哪 | 漏掉所有沉默失败(它不慢) |
| `error` 错误 | 0.22 | 谁报错了 | 漏掉"返回 200 但结果是错的" |
| `retry` 重试放大 | 0.10 | 谁的代价被重复付了 | 只在有重试时有效 |
| `tokens` 成本 | 0.14 | 钱烧在哪 | 一次运行只有一次模型调用时恒为满分 |
| `decision` 决策异常 | 0.16 | 哪个判断本身站不住 | 决策错但下游兜住了,就无信号 |
| `integrity` 完整性 | 0.18 | 有结果但结果是坏的 | 护栏报警会盖住真正的成因 |

三个细节决定了它能不能用:

1. **error 自底向上衰减(0.55)**,让最深的那个错误胜出。根节点会把所有子树的责任都揽下来,
   不衰减的话第一名永远是 `run`。
2. **完整性分"已落地 / 护栏拦下"两档**。试算接口拒绝执行 = 护栏报警,坏结果没落地,
   成因在上游决策;写入回显与入参不一致 = 坏结果已经落地,就是现场。两者同分时,
   "坏东西被生成出来"的位置永远排在"坏东西被发现"的位置后面。
3. **证据覆盖规则**。分数排序只回答"谁最可疑",清单还要回答"我凭什么能看见它"。
   一次运行里完整性失败的那个 Span 往往唯一能肉眼核对,它被几条同分决策挤出前三时,
   把最后一名换成它。

## 200 条故障注入评测

5 类故障 × 40 条,固定 seed,虚拟时钟驱动 —— 连跑三次结果逐位一致。

| 场景 | 说明 | R@1 | R@3 | MRR | 平均名次 |
|---|---|---|---|---|---|
| SLOW_TOOL | 单个工具变慢 | 1.0 | 1.0 | 1.0 | 1.0 |
| RETRY_STORM | 一个工具连续失败触发重试 | 1.0 | 1.0 | 1.0 | 1.0 |
| SILENT_FAILURE | 写入回显与入参不一致(无 ERROR) | 1.0 | 1.0 | 1.0 | 1.0 |
| COST_BLOWUP | prompt 膨胀,token 失控 | 1.0 | 1.0 | 1.0 | 1.0 |
| ARG_DRIFT | 缺金额走默认值,退 0 元 | 1.0 | 1.0 | 1.0 | 1.0 |
| **总体** | 200 条,平均 29.8 Span / 19.0 决策 | **1.0** | **1.0** | **1.0** | **1.0** |

三条基线(同样 200 条):

| 做法 | R@1 | 说明 |
|---|---|---|
| 第一个报错的 Span | 0.40 | 最直觉的做法。80 条沉默失败一个都抓不到 |
| 最慢的 Span | 0.20 | 慢 ≠ 错 |
| 最贵的 Span | 0.20 | 贵 ≠ 错 |

阅读量从 **29.8 个 Span 压到 3 张卡片**,下降 90%。

**这个 1.0 要打折看,两件事必须说清**:权重是在这 200 条样本上调的,没有留出集;
五个剧本只覆盖"已知的坏法",真实流量的分布远比这复杂。它度量的是"信号设计覆盖了故障分类",
不是"线上召回率 100%"。所以下面这段调参记录比结果更重要。

### 从 0.4 到 1.0:三次修改

| 阶段 | R@1 | 改了什么 | 为什么原来错 |
|---|---|---|---|
| 初版 | 0.40 | — | — |
| 修一 | 0.80 | 命中判定从 spanId 改成 Span 名 | 重试会生成多个同名 Span,金标准拿 spanId 永远对不上 |
| 修二 | 1.00 | 完整性分"落地 / 拦下"两档 | ARG_DRIFT 的根因是 D05 决策,但 top1 永远是试算拒绝的那个工具 |
| 修三 | 1.00 | 加证据覆盖规则 | 排序对了,但唯一能核对的那条证据被挤出前三 |

## 确定性来自哪里

评测不能靠"跑起来看情况"。两个东西保证它可复现:

- **虚拟时钟** `VirtualClock`:`sleep()` 只加 `nanos`,不真睡。200 条从分钟级降到毫秒级,
  而且耗时分布完全确定,判据里的阈值不会被机器负载影响。
- **故障注入按 seed 派生** `FaultPlan`:`(seed, taskId)` → 同一批故障序列,换机器也一样。

## 成本口径

| 指标 | 定义 | 为什么不用更简单的 |
|---|---|---|
| `costPerSuccessMicros` | 总成本 / 成功次数 | 摊到每次运行会奖励"少干活",摊到成功次数才反映真实代价 |
| `retryAmplification` | 尝试 Span 总数 / 首次尝试 Span 数 | 用 Span 总数当分母会把并行步骤算成放大 |
| `silentRate` | 无 ERROR 但完整性失败 / 总运行 | 沉默失败只能靠完整性信号发现 |

## 复现

```bash
mvn -q -DskipTests package                                   # Java 17+

java -jar agent-service/target/agent-service-1.0.0.jar       # :8081
java -jar gateway/target/gateway-1.0.0.jar                   # :8080
cd web-vue && npm i && npm run dev                            # :5173(Vue)
cd web-react && npm i && npm run dev                           # :5174(React)
```

- 跑一次:`POST /api/runs`,body `{"question":"A10003 我不想要了,能退多少退多少","scenario":"ARG_DRIFT"}`
- 看详情:`GET /api/runs/{traceId}`(响应头 `X-Trace-Id`)
- 导出:`GET /api/runs/{traceId}/export`(ndjson,一行一个 Span、一行一个决策,可直接进 Loki / ClickHouse)
- 评测:`POST /api/bench/triage`(200 条,秒级返回)
- 口径:`GET /api/metrics/overview`、`GET /api/meta/catalog`、`GET /api/meta/decisions`
- 测试:`mvn test`,49 个用例

## 踩过的坑(正文里有细节)

- `selfMs` 里扣子 Span 的耗时,用"总和相减"在并行步骤下会算出**负值**。要按区间并集合并,重叠只扣一次。
- `selfShare` 的分母用根的 `selfMs` 会除以 0 —— 子 Span 把根的时间全占了。除出来所有 Span 都 ≈1.0,排序直接失效。
- token 信号用"占本次运行比例"会系统性偏高:只有一次模型调用时它恒等于 100%。要对固定预算归一化。
- 采样不能影响本地存储。曾经采样规则把刚跑完的 trace 判为不采,前端拿到 404 —— 修法是"采样只决定上报,不决定存储"。
- `Map.of` 超过 10 对键直接编译失败,中文数字表要换 `HashMap`。
- `maven-compiler-plugin` 不显式开 `<parameters>true</parameters>`,`@PathVariable` 的名字在运行时是 `arg0`,接口 500。

## 目录

```
observability-core/src/main/java/com/example/o11y/
├── clock/       Clock / SystemClock / VirtualClock(评测用假时钟)
├── span/        Span / SpanKind / SpanStatus / Trace / Outcome / IntegrityGrade
├── decision/    DecisionPoint(20 个决策点)/ Decision / Basis
├── sample/      TailSampler(失败、低可信度、完整性失败必留)
├── redact/      Redactor(手机号/身份证/银行卡/邮箱/apiKey)
├── store/       TraceStore / InMemoryTraceStore
├── trace/       Tracer(begin/span/decision/attach/detach/finish)
├── triage/      TriageEngine(六信号打分)/ Suspect
└── export/      JsonlExporter

agent-service/src/main/java/com/example/agent/
├── pipeline/    AgentPipeline(20 个决策点全覆盖)/ IntentParser / SlotBinder
├── tool/        ToolRegistry / DemoTools(12 个电商工具)/ CircuitBreaker
├── fault/       Scenario(5 类故障)/ FaultPlan / FaultInjector
├── bench/       TriageBench(200 条确定性评测)/ BenchReport
├── metrics/     RunMetrics(Micrometer)
└── api/         RunController / MetaController / MetricsController / BenchController
```
