# Day 05 · 多 Agent 协作:任务拆错了怎么发现

Day04 解决的是「工具调用可不可靠」;这一篇解决「**计划本身是不是错的**」。

多 Agent 之后,最大的风险从"参数填错"变成"任务拆错"。而拆错最阴的地方是:
**每一步都返回 200**,只有用户最后发现不对。

## 三类拆错

| 缺陷 | 症状 | 谁能发现 | 修复动作 | 修复成本 |
|---|---|---|---|---|
| 拆漏 | 用户说了 3 件事,计划只做了 2 件 | 意图覆盖核对 | 机械补步(不用模型) | 低 |
| 拆错 | 依赖断开,退款金额由模型自己编 | 静态校验(参数溯源) | 重规划 | 高,且必须在写之前 |
| 拆多 | 一步能做完的事拆成两步 | 静态校验(重复步骤) | 重规划去冗余 | 中 |

## 三层防线

1. **静态校验**(`PlanValidator`):环、悬空依赖、引用未声明、字段无人产出、步数上限、重复步骤、写操作参数不可溯源。
2. **意图覆盖**(`IntentCoverage`):拿用户诉求(金标准槽位)和计划的 `covers` 对账,缺了哪个一目了然,并且能机械补步。
3. **事后复核**(`Critic`):核对承诺产出是否兑现、写操作是否落地、金额是否溯源。查不出"拆多",所以静态校验不能省。

## 四组对照(20 个任务 × 4 组配置,共 240 次任务执行)

| 配置 | 任务成功 | 假成功 | 被拦下 | 平均步数 | 平均延迟 | P95 | 重规划 |
|---|---|---|---|---|---|---|---|
| C0 无契约 | 10/20 (50.0%) | 10 | 0 | 3.6 | 439ms | 632ms | 0 |
| C1 静态校验 | 6/20 (30.0%) | 5 | 9 | 1.85 | 227ms | 625ms | 0 |
| C2 覆盖补步 | 11/20 (55.0%) | 0 | 9 | 2.1 | 255ms | 635ms | 0 |
| C3 复核重规划 | 20/20 (100%) | 0 | 0 | 3.65 | 447ms | 633ms | 9 |

按缺陷类型拆开看:

| 缺陷类型 | 任务数 | C0 | C1 | C2 | C3 |
|---|---|---|---|---|---|
| 干净 | 6 | 6 | 6 | 6 | 6 |
| 拆漏 | 5 | 0 | 0 | 5 | 5 |
| 拆错 | 5 | 0 | 0 | 0 | 5 |
| 拆多 | 4 | 4 | 0 | 0 | 4 |

三句话读完:

- **C1 比 C0 更差**(50% → 30%),因为它只会拦不会改。但 C1 的 9 次拦截意味着 **0 笔错误退款**,而 C0 把 5 笔金额编的退款真的发出去了。
- **C2 把漏项救回来**(30% → 55%),靠的是机械补步 —— 槽位和步骤一一对应,补一步不需要模型。
- **C3 把结构错误救回来**(55% → 100%),关键在"拦下即重规划",而且此时写操作还没落地,一切都可逆。

## 复现

```bash
mvn -q -DskipTests package
java -jar multi-agent-service/target/multi-agent-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar                          # :8080
cd web-vue && npm i && npm run dev                                   # :5173
cd web-react && npm i && npm run dev                                 # :5174
```

- 单任务:`POST /api/agent/run`(SSE,推 plan / validate / coverage / step / critic / replan / answer)
- 四组对照:`GET /api/bench`(约 30 秒,结果可复现)
- 测试:`mvn test`,49 个用例

## 踩过的坑(正文里有细节)

- 重规划之后退款金额还是错的 —— 幂等键只用了 `taskId + stepId`,改对之后的第二次请求被第一次的错误请求挡回去了。幂等键必须由**请求内容**派生。
- `Critic` 查不出"拆多":多调一次读接口,结果照样正确。只能靠静态校验。
- 写操作失败**绝不重规划重试**:重规划等于再发一次写请求,那是重复退款。

## 目录

```
multi-agent-service/src/main/java/com/example/multiagent/
├── plan/       Step / Plan / Refs / PlanValidator / StepTemplates / Slot / PlanFlaw
├── planner/    Planner / RulePlanner / PlanFlawInjector
├── coverage/   IntentCoverage / CoverageReport
├── critic/     Critic / Critique / Verdict
├── orch/       Coordinator / StepExecutor / GoldenCheck / CoordPolicy
├── tools/      电商售后 6 个工具(沿用 Day04)
├── bench/      CoordBench / BenchReport
└── api/        Agent(SSE) / Bench / Catalog
```
