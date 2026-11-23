# Day 02 · Redis 会话记忆 + 滚动摘要压缩

> 让 Agent 记住 50 轮对话,而且不烧钱、不爆窗口、不失忆。

Day 01 的 `ConversationMemory` 是一个进程内的滑动窗口:超过 20 条就从最旧的开始丢。
它能在 demo 里跑得很好,但一上线就暴露三个问题:

| 问题 | 表现 | 本项目的解法 |
| --- | --- | --- |
| 没有持久化 | 服务重启、换实例、用户换设备 → 记忆归零 | Redis 存会话状态,版本号 + Lua CAS |
| 无脑丢弃 | 第 3 轮说过的订单号,第 20 轮就"忘了" | 滚动摘要 + 关键事实双轨,信息不丢 |
| 全量回放 | 50 轮 ≈ 2 万 token,贵、慢、超窗口直接 400 | 压缩到固定预算内,历史进归档按需召回 |

## 一句话架构

```
请求 ──▶ 会话锁(同会话串行)
          ├─ 追加 user 消息并落库
          ├─ 水位判断 → 折叠最早的整轮 → 摘要模型合并进「已有摘要」
          │            └─ 原文写入归档(Redis LIST,上限 400 条)
          ├─ 归档召回(BM25-lite,把相关旧片段注入 system)
          └─ LLM 循环(每步落库)
                └─> 上下文 = 规则 + 工具 + 摘要 + 关键事实 + 召回片段 + 活跃窗口
```

## 目录

```
day02-memory-redis/
├── memory-service/                 # 核心服务(:8081)
│   └── src/main/java/com/example/memory/
│       ├── domain/                 # Msg(带 seq)/ Conversation(聚合根)/ ToolCall
│       ├── store/                  # ChatMemoryStore + 内存/Redis 两实现 + 分段锁
│       ├── memory/                 # 压缩器、分词召回、关键事实、token 估算、兜底摘要
│       ├── agent/                  # 编排器 + 会话生命周期服务
│       └── api/                    # SSE 对话 + 会话/记忆管理接口
├── gateway/                        # Spring Cloud Gateway(:8080),SSE 超时与 CORS
├── web-vue/                        # Vue 3 + TS:三栏布局 + 记忆面板
├── web-react/                      # React 18 + TS:同一套交互,验证接口契约
└── docker-compose.yml              # 一条命令起 Redis
```

## 快速开始

### 1) 零依赖跑起来(内存存储 + mock 模型)

```bash
mvn -q -DskipTests package
java -jar memory-service/target/memory-service-1.0.0.jar      # :8081
java -jar gateway/target/gateway-1.0.0.jar                    # :8080
cd web-vue && npm i && npm run dev                            # :5173
```

打开 <http://localhost:5173>,连发 6~8 条消息,右侧「记忆面板」会看到:

- 水位条回落(压缩触发)
- 摘要正文与关键事实 chips
- 归档条数与召回打分

### 2) 切到 Redis(跨实例持久化)

```bash
docker compose up -d redis
java -jar memory-service/target/memory-service-1.0.0.jar \
     --agent.memory.store=redis --spring.data.redis.port=6379
```

### 3) 接真实模型

```bash
export AGENT_LLM_API_KEY=sk-xxx
java -jar memory-service/target/memory-service-1.0.0.jar \
     --agent.provider=openai \
     --agent.llm.base-url=https://api.deepseek.com/v1 \
     --agent.llm.model=deepseek-chat
```

> 任何 OpenAI 兼容网关都能用(DeepSeek / 通义 / Moonshot / vLLM / Ollama `/v1`)。

## 关键设计

### 1. 压缩只按「整轮」切

```java
// TurnSegmenter:以 user 消息为边界切段
用户: 我要退款
助手: [调用工具 create_support_ticket]   ← 同一轮,一起删或一起留
工具: 工单号 T20261123001
助手: 已创建
```

按消息条数裁剪会把 `assistant(tool_calls)` 和 `tool` 结果拆散,下一次请求直接 400。
按整轮切分,序列合法性是<b>结构性保证</b>,不是靠事后修补。

### 2. 摘要 + 关键事实双轨

纯 LLM 摘要压 5 次后,`订单 A20261123001` 会变成"该订单"。所以数字、单号、金额、日期
由正则从被折叠的消息里抽出来,单独存 `pinnedFacts`,每轮原样注入 system。
规则召回率不如模型,但 100% 保真、零成本、可单测。

### 3. 失败必须降级

```
摘要模型超时/429 → 本地规则摘要(保住目标 + 单号 + 最后诉求)
连续失败 3 次    → 熔断 5 分钟,期间直接走本地摘要
压缩收益为负     → 回滚,不做无用功(新摘要比折叠内容还长时)
```

### 4. 并发与一致性

- **会话级分段锁**:同会话串行,不同会话并行(全局锁会让吞吐掉到个位数)
- **Redis Lua CAS**:`GET → 比较 → SET` 的竞态在 Lua 里不可能发生
- **冲突宁可失败**:CAS 冲突返回可重试错误,绝不把两份历史合并成四不像

## 实测数据

`LongConversationTest` 用 mock 模型跑 50 轮(预算 1200 token):

```
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

全量回放需要 ≈ 7400 token,压缩后稳定在 743 token —— **省了 90%**,而且订单号一次都没丢。

## 测试

```bash
mvn test                                    # 35 个单测
REDIS_TEST_PORT=6390 mvn test               # 额外跑 7 个 Redis 集成测试
```

Redis 集成测试会自动跳过(没起 Redis 时用 `Assumptions`),不会让 CI 红。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat/stream` | SSE 对话(含 `memory_compacted` / `memory_recall` 事件) |
| POST | `/api/chat` | 非流式,便于 curl 与压测 |
| GET | `/api/conversations?userId=` | 会话列表(只返回摘要,不返回全量消息) |
| GET | `/api/conversations/{id}` | 会话详情:摘要、关键事实、水位 |
| POST | `/api/conversations/{id}/compact?force=true` | 手动压缩(复现问题用) |
| GET | `/api/conversations/{id}/archive?q=` | 归档原文 / 召回打分 |
| DELETE | `/api/conversations/{id}` | 删除会话与归档 |
| GET | `/api/memory/stats` | 存储类型、会话数、熔断状态 |

## 已知取舍

- **本地锁 + Redis CAS**:多实例下同会话并发会以 409 失败而不是排队,要真正串行需上 Redisson 分布式锁。
- **token 估算**按字符类型折算,误差 ±15%;生产上应拿 `usage.prompt_tokens` 反算校准系数。
- **召回是词面匹配**,口语化很强的问题召回率有限;跨会话检索应换向量检索(接口已抽象好)。

## 配套文章

CSDN 系列第 2 篇:Redis 会话记忆 + 滚动摘要压缩 + 归档召回。
