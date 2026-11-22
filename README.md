# ai-agent-lab

手写 AI Agent 的系列实战代码。每个目录对应 CSDN 上的一篇文章,可以独立运行。

| 日期 | 目录 | 主题 | 技术栈 |
| --- | --- | --- | --- |
| Day 01 | [`day01-agent-core`](./day01-agent-core) | 从零手写支持 Function Calling 与 SSE 流式输出的 AI Agent 服务 | Spring Boot 3.3 · Spring Cloud Gateway 2023 · Vue 3 · React 18 · SSE |

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

## 路线图

- [x] Day 01 Agent 编排核心 + SSE + 双前端
- [ ] Day 02 会话持久化:Redis 记忆 + 摘要压缩,支撑 50 轮长对话
- [ ] Day 03 RAG:向量检索接入 + 召回评测集,量化效果
- [ ] Day 04 多 Agent 协作:Planner / Executor 拆分与任务编排
- [ ] Day 05 可观测与成本:全链路 trace、token 计费看板

## License

MIT
