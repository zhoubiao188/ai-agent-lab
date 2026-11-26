# Day 04 · 工具调用可靠性

把「Agent 调工具」从 demo 做到能上线:参数自纠正、超时重试、依赖并行、熔断降级,
以及一套**确定性的故障注入器**——没有它,你写的重试逻辑可能从来没被触发过。

```
任务 ──▶ 规划器 ──▶ 参数预检/修复 ──▶ 依赖就绪调度 ──▶ 每个调用:
                        │                  │              ├─ 预算检查
                        │                  │              ├─ 熔断放行
                        ▼                  │              ├─ 故障注入(按 seed 确定性)
                  回灌模型一次             │              ├─ 带超时执行
                  (只回灌一次)             │              ├─ 错误分类
                                           │              ├─ 指数退避 + 抖动重试
                                           ▼              └─ 读操作可降级 / 写操作绝不
                                       结果汇总 ──▶ SSE 时间线
```

## 实测数据

同一批 120 个任务 × 4 组配置(共 2880 次工具调用),同一个 seed,同一批故障序列:

| 配置 | 成功率 | 成功任务均值 | P95 | 重试次数 |
| --- | --- | --- | --- | --- |
| L0 裸调用 | 36.7% | 90ms | 96ms | 0 |
| L1 + 重试 | 65.0% | 111ms | 186ms | 60 |
| L2 + 参数修复 | 87.5% | 111ms | 173ms | 65 |
| L3 + 并行/熔断/降级 | 87.5% | 59ms | 100ms | 65 |

L3 成功率没有再涨,是因为剩下的失败全部来自**重试本来就不该救**的两类:
业务规则拒绝(5 次)和非幂等写接口(工单接口单次超时后不允许重试)。
把工单接口改成幂等(带幂等键)之后同样跑一遍:L2 95.0%,L3 95.0%。

> 机制的上限由业务语义决定。改代码之前,先改语义。

## 六个工具

| 工具 | 类型 | 幂等 | 说明 |
| --- | --- | --- | --- |
| `order.query` | 读 | 是 | 订单详情 |
| `logistics.track` | 读 | 是 | 物流轨迹,最慢(200ms),用来暴露串行瓶颈 |
| `inventory.check` | 读 | 是 | 库存,数量是 integer,专门用来验证"小数转整数必须拒绝" |
| `refund.quote` | 读 | 是 | 计算可退金额,依赖订单 |
| `refund.submit` | 写 | **是(幂等键)** | 依赖报价单,重试安全 |
| `ticket.create` | 写 | **否** | 非幂等,重试开关永远关闭 |

## 快速开始

```bash
mvn -q -DskipTests package
java -jar tool-service/target/tool-service-1.0.0.jar   # :8081
java -jar gateway/target/gateway-1.0.0.jar            # :8080

cd web-vue   && npm i && npm run dev   # :5173(Vue 3)
cd web-react && npm i && npm run dev   # :5174(React 18)
```

不用装任何外部依赖:工具、LLM、向量库全是进程内的确定性实现,评测可复现。

## 接口

```bash
# 实时任务(SSE 事件流:plan / planned / repair / retry / tool_start / tool_ok / tool_fail / done)
curl -N -X POST localhost:8080/api/agent/run \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"task":"处理订单 A10003 的退款申请","policy":"full","faults":true,"seed":20261126}'

# 工具清单 / 单工具直调(排障用:绕过模型直接打工具)
curl localhost:8080/api/tools
curl -X POST localhost:8080/api/tools/order.query/invoke \
  -H 'Content-Type: application/json' -d '{"order_id":"A10003"}'

# 对照实验
curl -X POST localhost:8080/api/bench/run \
  -H 'Content-Type: application/json' -d '{"tasks":120,"seed":20261126,"scale":0.1}'
```

`policy` 取值:`l0` / `l1` / `l2` / `full`。同一个 `seed` + 同一个任务,故障序列完全一致。

## 目录

```
tool-service/src/main/java/com/example/tool
├── api/            AgentController(SSE) · ToolsController · BenchController · MetricsController
├── agent/          AgentOrchestrator · MockPlanner · Planner · TaskOutcome
├── spec/           JsonSchema · JsonSchemaValidator · ToolSpec · ToolRegistry
├── repair/         ArgumentRepairer(确定性修复)→ RepairOutcome
├── exec/           ToolInvoker · RetryPolicy · CircuitBreaker · ParallelToolExecutor · ToolMetrics
├── fault/          FaultInjector(确定性)· CorruptionKind
├── tools/          EcommerceTools(6 个工具)· LatencyGate
└── bench/          BenchRunner · BenchReport
```

## 测试

60 个测试,覆盖错误分类、退避上限、熔断半开、非幂等写不重试、依赖失败跳过、
`$call.field` 引用解析、参数修复七条规则、以及两组对照实验本身。

```bash
JAVA_HOME=/path/to/jdk17+ mvn test
```

## 设计取舍

- **校验器不做类型转换**:转换交给修复器,谁干的活可度量。早期版本让校验器自动转 `"12"→12`,
  结果修复逻辑永远不触发,报表上"参数错误 0 次",而线上退款金额全被截断。
- **小数转整数直接拒绝**:`12.7 → 12` 是资金事故的起点,宁可失败。
- **重试不放在网关**:网关看不到幂等语义,一次超时重试就可能变成两笔退款。
- **降级只给读操作**:写操作降级返回"成功"不是降级,是伪造。
- **预检放过 `$ref` 占位符**:占位符要到执行时才有值,把它当类型错误去修只会白问模型一轮。
- **延迟只统计成功的任务**:失败往往几毫秒返回,混进去会得出"越不靠谱越快"。

## License

MIT
