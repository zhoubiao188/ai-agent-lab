# 从零手写 AI Agent:Spring Boot 3 + Spring Cloud Gateway + Vue3 / React

> 系列第 1 篇 —— 不依赖任何 Agent 框架,用 600 行 Java 把 **流式输出 + Function Calling + 多轮工具编排 + 会话记忆** 全部写清楚。

## 这个项目能干什么

一个可运行的"会用工具的 AI 助手"服务端:

- 用户提问 → 模型判断需不需要调工具 → 调用工具 → 把结果喂回模型 → 生成最终答案
- 整个过程通过 **SSE** 逐字推给前端,前端能实时看到「正在调用哪个工具、参数是什么、返回了什么」
- 一次可以**并行触发多个工具**(比如同时查天气 + 算数学)
- 带会话记忆、限流、超时、指标、幂等写操作

**克隆下来不需要任何 API Key 就能跑通全链路**:内置 `mock` provider 会扮演一个"会用工具的模型",配置一个 Key 之后切到真实模型,业务代码一行不改。

## 架构

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

## 快速开始

环境:JDK 17+、Maven 3.9+、Node 18+

```bash
# 1. 后端(默认 mock 模式,无需 Key)
mvn -q -DskipTests package
java -jar agent-service/target/agent-service-1.0.0.jar     # :8081
java -jar gateway/target/gateway-1.0.0.jar                # :8080

# 2. 前端(任选一个)
cd web-vue   && npm i && npm run dev    # http://localhost:5173
cd web-react && npm i && npm run dev    # http://localhost:5174
```

命令行验证流式接口:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"北京今天天气怎么样?顺便帮我算一下 (128.5+71.5)*3/2"}'
```

你会依次看到 `message_start` → `tool_start` ×2 → `tool_result` ×2 → `delta` ×N → `message_end` → `done`。

## 接入真实模型

任意兼容 OpenAI `/chat/completions` 的服务都可以(DeepSeek、通义、Moonshot、vLLM、Ollama 的 `/v1`):

```bash
export AGENT_LLM_API_KEY=sk-xxxx
java -jar agent-service/target/agent-service-1.0.0.jar \
  --agent.provider=openai \
  --agent.llm.base-url=https://api.deepseek.com/v1 \
  --agent.llm.model=deepseek-chat
```

或直接改 `agent-service/src/main/resources/application.yml`。

## 关键设计说明

| 问题 | 做法 | 为什么 |
| --- | --- | --- |
| 为什么用 SSE 不用 WebSocket | POST + `text/event-stream` | 单向下行,走普通 HTTP,网关/鉴权/日志都能复用;WebSocket 要处理升级、心跳、粘性会话 |
| 工具调用的参数怎么拼 | 按 `index` 聚合 `tool_calls` 增量 | OpenAI 协议里 arguments 是多帧碎片,第一帧才带 id/name |
| 工具超时 | 独立线程池 + `Future.get(15s)` | 一个卡死的三方接口不能拖垮整条对话 |
| 死循环 | `maxSteps` 上限 + 兜底话术 | 模型可能无限调工具烧 token |
| 记忆窗口 | 滑动窗口 + tool 消息成对裁剪 | 丢掉 assistant(tool_calls) 却留 tool 结果,下次请求会被模型侧 400 |
| 重复提交 | 会话内幂等键 | 模型对写操作没有幂等意识 |
| 表达式计算 | 自己写递归下降解析器 | 用 SpEL/ScriptEngine 等于把 RCE 送上门 |
| 前端解析 | fetch + ReadableStream 手写 SSE 解析 | `EventSource` 只支持 GET,且会"断线自动重连"导致重复推理 |

## 目录结构

```
day01-agent-core/
├── agent-service/                     # Spring Boot 3 核心服务
│   └── src/main/java/com/example/agent/
│       ├── AgentServiceApplication.java
│       ├── agent/
│       │   ├── AgentOrchestrator.java  # ★ 核心:Agent 循环
│       │   ├── AgentEventSink.java     # 事件输出抽象(可测)
│       │   └── ConversationMemory.java # 滑动窗口记忆
│       ├── llm/
│       │   ├── LlmProvider.java        # 供应商抽象
│       │   ├── OpenAiCompatLlmProvider.java  # 流式 SSE 解析
│       │   └── MockLlmProvider.java    # 离线演示
│       ├── tool/
│       │   ├── AgentTool.java          # 工具接口
│       │   ├── ToolRegistry.java       # 自动注册
│       │   └── impl/                   # 5 个真实工具
│       ├── api/                        # REST + SSE 端点
│       ├── ratelimit/                  # 令牌桶
│       └── observe/                    # Micrometer 指标
├── gateway/                           # Spring Cloud Gateway
├── web-vue/                           # Vue 3 + TS + Vite
└── web-react/                         # React 18 + TS + Vite
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/stream` | SSE 流式对话(主接口) |
| POST | `/api/chat` | 非流式,调试/服务间调用 |
| GET | `/api/chat/{cid}/history` | 查看会话记忆 |
| DELETE | `/api/chat/{cid}` | 清空会话 |
| GET | `/api/agent/info` | provider / 模型 / 工具清单 |
| GET | `/api/agent/tools` | 工具 JSON Schema |
| GET | `/actuator/prometheus` | 指标 |

SSE 事件:

```jsonc
event: message_start   data: {"conversationId":"...","provider":"mock","realModel":false}
event: delta           data: {"text":"北"}
event: delta_reset     data: {"reason":"tool_call_started"}   // 撤回工具调用前的过渡语
event: tool_start      data: {"index":0,"id":"call_1","name":"get_weather"}
event: tool_result     data: {"id":"call_1","name":"get_weather","success":true,"content":"...","elapsedMs":3}
event: message_end     data: {"content":"...","steps":2,"finishReason":"stop"}
event: done            data: {"steps":2,"promptTokens":120,"completionTokens":80,"elapsedMs":1830}
event: error           data: {"message":"...","type":"..."}
```

## 测试

```bash
mvn -pl agent-service test
```

15 个用例,覆盖:表达式解析(含注入攻击)、BM25 检索、多工具并行执行、幂等工单、记忆裁剪合法性。

## 已知边界 / 下一步

- 记忆在进程内,重启即丢 → 第 2 篇换 Redis,并加"摘要压缩"以支撑长对话
- 检索是词面 BM25 → 第 3 篇接向量库,并用 RAG 评测集量化召回提升
- 工具串行执行 → 无依赖的工具可以并发,把 P99 从 `sum(t)` 降到 `max(t)`
- 无鉴权 → 生产需接 Spring Security + 按用户维度计费/配额

## License

MIT
