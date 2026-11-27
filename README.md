# ai-agent-lab

手写 AI Agent 的系列实战代码。每个目录对应 CSDN 上的一篇文章,可以独立运行。

| 日期 | 目录 | 主题 | 技术栈 |
| --- | --- | --- | --- |
| Day 01 | [`day01-agent-core`](./day01-agent-core) | 从零手写支持 Function Calling 与 SSE 流式输出的 AI Agent 服务 | Spring Boot 3.3 · Spring Cloud Gateway 2023 · Vue 3 · React 18 · SSE |
| Day 02 | [`day02-memory-redis`](./day02-memory-redis) | Redis 会话记忆 + 滚动摘要压缩 + 归档召回,支撑 50 轮长对话 | Spring Boot 3.3 · Redis(Lettuce + Lua) · Spring Cloud Gateway · Vue 3 · React 18 |
| Day 03 | [`day03-rag-vector`](./day03-rag-vector) | RAG 检索工程化:分块 + 混合召回(RRF)+ 特征重排 + 50 条召回评测集 | Spring Boot 3.3 · Spring Cloud Gateway · BM25/向量/IVF · Vue 3 · React 18 |
| Day 04 | [`day04-tool-reliability`](./day04-tool-reliability) | 工具调用可靠性:参数自纠正 + 超时重试 + 依赖并行 + 熔断降级 + 故障注入 | Spring Boot 3.3 · Spring Cloud Gateway · JSON Schema · Vue 3 · React 18 |
| Day 05 | [`day05-multi-agent`](./day05-multi-agent) | 多 Agent 协作:计划契约 + 静态校验 + 意图覆盖 + Critic 复核 + 一次重规划 | Spring Boot 3.3 · Spring Cloud Gateway · DAG 计划 · Vue 3 · React 18 |

## 为什么不用现成框架

Spring AI / LangChain4j 这类框架把 Agent 抽象得很好,但它们封装掉的部分恰恰是线上出问题时
你唯一需要看懂的部分:

- 工具调用的 arguments 是**增量分帧**到达的,按什么规则聚合?
- 模型同一步里先吐了一段"我先查一下"再发起工具调用,这段文本怎么撤回?
- 工具卡死 30 秒,怎么做到只中断工具而不炸掉整条对话?
- 记忆裁剪时丢了 `assistant(tool_calls)` 却留下 `tool` 结果,为什么下一次请求直接 400?

这个系列把这些问题一条条摊开写。先手写一遍,再选框架,才知道框架替你做了什么。

## 各篇速览

### Day 01 · agent-core

```
用户提问 ──▶ LLM(带工具声明) ──流式──▶ 前端
              ├─ 只回文本        ──▶ 结束
              └─ 要调工具       ──▶ 执行 ──▶ 结果回填 ──▶ 下一轮(最多 maxSteps)
```

- 5 个真实工具:时间、计算器(手写递归下降解析器,零 RCE 风险)、天气、BM25 知识库检索、幂等工单
- 双 provider:`mock`(无需 Key,开箱即跑)/ `openai`(兼容 DeepSeek、通义、Moonshot、vLLM、Ollama)
- 生产必备件:滑动窗口记忆、令牌桶限流、工具级超时、Micrometer 指标、`X-Request-Id` 全链路追踪
- 15 个单元测试 + GitHub Actions CI

```bash
cd day01-agent-core
mvn -q -DskipTests package
java -jar agent-service/target/agent-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar              # :8080
cd web-vue && npm i && npm run dev                       # :5173
```

### Day 02 · memory-redis

```
用户提问 ──▶ 会话锁 ──▶ 水位判断 ──▶ 折叠最早整轮 ──▶ 摘要合并(增量)
              │                        └─▶ 归档 Redis LIST ──┐
              ├──▶ 归档召回(BM25-lite)◀───────────────────────┘
              └──▶ 上下文 = 规则 + 工具 + 摘要 + 关键事实 + 召回片段 + 活跃窗口
```

- 压缩只按整轮切,结构上保证 `assistant(tool_calls)` 与 `tool` 结果不被拆散
- 摘要 + 关键事实双轨:单号/金额/日期由正则保真,不交给模型改写
- 失败降级链:摘要超时 → 本地规则摘要 → 连续失败熔断
- 50 轮压测:7400 token 全量回放 → 743 token 稳定窗口,压缩 15 次,关键信息零丢失
- 35 个单测 + 7 个 Redis 集成测试(Lua CAS / LTRIM / TTL 全覆盖)

```bash
cd day02-memory-redis
mvn -q -DskipTests package
java -jar memory-service/target/memory-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar                 # :8080
cd web-vue && npm i && npm run dev                          # :5173
```

### Day 03 · rag-vector

```
文档 ──▶ 结构化分块(## 小节 → 句子 → 代码块原子)
          ├──▶ BM25 倒排(中文 bigram)──┐
          └──▶ 向量索引(flat / IVF)──┤
                                      ▼
                          RRF 融合 ──▶ 特征重排 ──▶ top-k 上下文
                                      ▼
                             引用式 SSE 生成(citations → delta)
```

- 分块三层:先按 `##` 切小节,再按句子打包到 520 字,相邻块重叠 90 字;代码围栏整块不切
- 中文不引第三方分词器,bigram + 英文驼峰拆解,BM25 分词与向量分词彻底解耦
- 融合用 RRF(只用名次,对量纲免疫),重排用五个可解释特征,α 由评测集扫描标定(实测 0.2 最优)
- 50 条人工标注评测集,输出 Recall@1/3/5、MRR、NDCG@5、P50/P95,按查询类型拆解 + 失败样本
- 实测:BM25 MRR 0.953 > 向量 0.913;融合把 R@5 从 0.97 提到 0.98;重排只值 +0.009 MRR
- 零依赖可跑:本地字符 n-gram 哈希向量 + mock LLM;换环境变量即可接 OpenAI / DeepSeek

```bash
cd day03-rag-vector
mvn -q -DskipTests package
java -jar rag-service/target/rag-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar           # :8080
cd web-vue && npm i && npm run dev                    # :5173(Vue)
cd web-react && npm i && npm run dev                  # :5174(React)
```

### Day 04 · tool-reliability

```
任务 ──▶ 规划器 ──▶ 参数预检/修复 ──▶ 依赖就绪调度 ──▶ 单次调用:
                        │                 │              ├─ 预算 → 熔断 → 故障注入
                        ▼                 │              ├─ 带超时执行 → 错误分类
                  回灌模型一次            │              ├─ 指数退避 + 抖动重试
                  (只回灌一次)            │              └─ 读可降级 / 写绝不
                                          ▼
                                      结果汇总 + SSE 时间线
```

- 参数修复两层:确定性规则(键名别名/字符串转数字/枚举归一/丢多余字段)+ 把剩余错误原文回灌一次
- 重试四条铁律:只重试可重试类型、非幂等写一律不重试、退避带抖动、Retry-After 超上限就放弃
- 并行不是分层而是依赖就绪调度:6 个调用从 723ms 降到 352ms;分层会让报价单白等物流 200ms
- 熔断按工具独立 + 半开单探测 + 最小样本量,避免低流量工具被偶发抖动熔断
- 确定性故障注入:同 seed + 同任务 → 同一批故障序列,L0~L3 四组配置可比
- 实测 120 任务 × 4 配置:36.7% → 65.0% → 87.5% → 87.5%,失败分解显示最后 12.5% 全是业务拒绝与非幂等写接口
- 把工单接口改成幂等后再跑:L2/L3 均 95.0% —— 机制的天花板由业务语义决定
- 60 个测试,含两组对照实验本身

```bash
cd day04-tool-reliability
mvn -q -DskipTests package
java -jar tool-service/target/tool-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar             # :8080
cd web-vue && npm i && npm run dev                     # :5173(Vue)
cd web-react && npm i && npm run dev                   # :5174(React)
```

### Day 05 · multi-agent

```
用户诉求 ──▶ Planner ──▶ 计划(DAG) ──▶ 静态校验 ──┬─ 不通过 ──▶ 重规划一次(只改结构)
                          │                      │
                          ├─▶ 意图覆盖核对 ──────┴─ 缺口 ──▶ 机械补步(不用模型)
                          │
                          ▼
                    Executor(按拓扑序,复用已完成步骤)
                          │
                          ▼
                    Critic ──▶ PASS / PATCH / REPLAN / FAIL ──▶ 交付或如实报告
```

- 计划不是列表而是 DAG:环、悬空依赖、数据流断没断,只有图能表达
- 每个步骤声明 `covers / produces / consumes / dependsOn`,计划才从"一串调用"变成"可以被验收的契约"
- 三类拆错三种修复成本:拆漏(机械补步,便宜)、拆错(必须重规划,且要在写之前)、拆多(只能静态看)
- 静态校验九条规则,真正救命的是三条:引用未声明依赖、重复步骤、写操作参数不可溯源
- 事后复核查"承诺是否兑现"而不是"步骤是否成功";但查不出拆多,所以两层都不能省
- 重规划三条纪律:只改结构、复用已完成步骤、写失败绝不重试;幂等键必须由请求内容派生
- 实测 20 任务 × 4 配置:C0 50.0%(假成功 10)→ C1 30.0%(只拦不修,但错误写操作 5 → 0)→ C2 55.0% → C3 100.0%,重规划 9 次
- 49 个测试,含"假成功""拆多不可复核""重规划不重放写操作"的固化用例
- 配套文章:[多 Agent 把任务拆错了,为什么每一步都返回 200](https://blog.csdn.net/qwe86314/article/details/171478904)

```bash
cd day05-multi-agent
mvn -q -DskipTests package
java -jar multi-agent-service/target/multi-agent-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar                          # :8080
cd web-vue && npm i && npm run dev                                   # :5173(Vue)
cd web-react && npm i && npm run dev                                 # :5174(React)
```

## 路线图

- [x] Day 01 Agent 编排核心 + SSE + 双前端
- [x] Day 02 会话持久化:Redis 记忆 + 摘要压缩 + 归档召回,支撑 50 轮长对话
- [x] Day 03 RAG:混合检索 + 重排 + 召回评测集,量化效果
- [x] Day 04 工具调用可靠性:并行、超时重试、参数自纠正、故障注入
- [x] Day 05 多 Agent 协作:计划契约、意图覆盖、Critic 复核与重规划
- [ ] Day 06 可观测与成本:全链路 trace、token 计费看板

## License

MIT
