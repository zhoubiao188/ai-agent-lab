import { useCallback, useEffect, useMemo, useState } from 'react'
import Timeline from './components/Timeline'
import BenchTable from './components/BenchTable'
import { fetchTools, runBench, streamRun } from './api'
import type { AgentEvent, BenchReport, Lane, RunResult, ToolSpec } from './types'

function newLane(callId: string, tool: string, dependsOn: string[]): Lane {
  return {
    id: callId,
    tool,
    dependsOn,
    start: 0,
    end: null,
    status: 'running',
    attempts: 0,
    retries: 0,
    repairs: []
  }
}

/**
 * 事件 → 泳道。纯函数,方便单测和 reason 追踪。
 * 这里不按事件顺序"画图",而是维护每条调用的生命周期,最后由渲染层按时间轴布局 ——
 * 并行调用的事件天然是交错的,顺序画会得到一张错的时间线。
 */
function applyEvent(prev: Lane[], e: AgentEvent): Lane[] {
  const patch = (id: string, fn: (l: Lane) => Lane): Lane[] =>
    prev.map((l) => (l.id === id ? fn(l) : l))

  switch (e.type) {
    case 'planned': {
      const calls: { callId: string; tool: string; dependsOn?: string[] }[] = e.data.calls ?? []
      const next = [...prev]
      for (const c of calls) {
        if (!next.some((l) => l.id === c.callId)) {
          const lane = newLane(c.callId, c.tool, c.dependsOn ?? [])
          lane.start = e.t
          next.push(lane)
        }
      }
      return next
    }
    case 'tool_start': {
      if (!prev.some((l) => l.id === e.data.callId)) {
        return [...prev, { ...newLane(e.data.callId, e.data.tool, []), attempts: 1 }]
      }
      return patch(e.data.callId, (l) => ({ ...l, attempts: l.attempts + 1, status: 'running' }))
    }
    case 'retry':
      return patch(e.data.callId, (l) => ({ ...l, retries: l.retries + 1 }))
    case 'repair':
      return patch(e.data.callId, (l) => ({
        ...l,
        repairs: [...l.repairs, ...((e.data.repairs as string[]) ?? [])]
      }))
    case 'tool_ok':
      return patch(e.data.callId, (l) => ({
        ...l,
        end: (e.data.at as number) ?? e.t,
        status: l.status === 'degraded' ? 'degraded' : 'ok'
      }))
    case 'tool_fail':
      return patch(e.data.callId, (l) => ({
        ...l,
        end: e.t,
        status: 'failed',
        error: `${e.data.kind}: ${e.data.error ?? ''}`
      }))
    case 'tool_fallback':
      return patch(e.data.callId, (l) => ({ ...l, status: 'degraded' }))
    case 'tool_skip':
      return patch(e.data.callId, (l) => ({
        ...l,
        end: e.t,
        status: 'skipped',
        error: String(e.data.reason ?? '')
      }))
    default:
      return prev
  }
}

export default function App() {
  const [task, setTask] = useState('处理订单 A10003 的退款申请,并跟进物流异常')
  const [policy, setPolicy] = useState('full')
  const [faults, setFaults] = useState(true)
  const [seed, setSeed] = useState(20261126)
  const [running, setRunning] = useState(false)
  const [lanes, setLanes] = useState<Lane[]>([])
  const [events, setEvents] = useState<{ type: string; t: number }[]>([])
  const [result, setResult] = useState<RunResult | null>(null)
  const [error, setError] = useState('')
  const [tools, setTools] = useState<ToolSpec[]>([])
  const [bench, setBench] = useState<BenchReport | null>(null)
  const [benchLoading, setBenchLoading] = useState(false)

  useEffect(() => {
    fetchTools()
      .then(setTools)
      .catch(() => setError('后端没起来?先执行 mvn -pl tool-service spring-boot:run'))
  }, [])

  const total = useMemo(() => {
    if (result) return Math.max(result.wallMs, 1)
    return Math.max(1, ...lanes.map((l) => l.end ?? l.start))
  }, [result, lanes])

  const retries = lanes.reduce((n, l) => n + l.retries, 0)
  const repairs = lanes.reduce((n, l) => n + l.repairs.length, 0)
  const degraded = lanes.filter((l) => l.status === 'degraded').length
  const failed = lanes.filter((l) => l.status === 'failed' || l.status === 'skipped').length

  const handleEvent = useCallback((e: AgentEvent) => {
    setEvents((prev) => [...prev, { type: e.type, t: e.t }].slice(-400))
    if (e.type === 'result') {
      setResult(e.data as unknown as RunResult)
      return
    }
    if (e.type === 'error') {
      setError(String(e.data.message ?? '未知错误'))
      return
    }
    setLanes((prev) => applyEvent(prev, e))
  }, [])

  const run = async () => {
    setRunning(true)
    setError('')
    setResult(null)
    setLanes([])
    setEvents([])
    try {
      await streamRun({ task, policy, faults, seed }, handleEvent)
    } catch (err) {
      setError(String(err))
    } finally {
      setRunning(false)
    }
  }

  const doBench = async () => {
    setBenchLoading(true)
    try {
      setBench(await runBench(120, seed))
    } catch (err) {
      setError(String(err))
    } finally {
      setBenchLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="top">
        <h1>Agent 工具调用可靠性控制台</h1>
        <span className="sub">
          Spring Boot 3 · Spring Cloud Gateway · React —— 参数修复 / 超时重试 / 依赖并行 / 熔断降级
        </span>
      </header>

      <div className="grid">
        <div>
          <section className="card">
            <h2>任务</h2>
            <label className="field" htmlFor="task">
              一句话任务(带订单号)
            </label>
            <textarea
              id="task"
              className="task"
              value={task}
              onChange={(e) => setTask(e.target.value)}
            />
            <div className="row">
              <select value={policy} onChange={(e) => setPolicy(e.target.value)}>
                <option value="l0">L0 · 裸调用</option>
                <option value="l1">L1 · 加重试</option>
                <option value="l2">L2 · 加重试+修复</option>
                <option value="full">L3 · 全量(并行+熔断+降级)</option>
              </select>
              <button className="primary" disabled={running} onClick={run}>
                {running ? '运行中…' : '运行'}
              </button>
            </div>
            <div className="row switches">
              <label>
                <input
                  type="checkbox"
                  checked={faults}
                  onChange={(e) => setFaults(e.target.checked)}
                />
                注入故障(超时/429/5xx/业务拒绝/参数污染)
              </label>
              <label>
                seed
                <input
                  className="num"
                  type="number"
                  style={{ width: 108 }}
                  value={seed}
                  onChange={(e) => setSeed(Number(e.target.value))}
                />
              </label>
            </div>
            <p className="hint">同一个 seed + 同一个任务,故障序列完全一致,方便对着时间线复盘。</p>
          </section>

          <section className="card">
            <h2>工具清单</h2>
            <div className="tools">
              {tools.map((t) => (
                <span className="tool-chip" key={t.name}>
                  {t.name}
                  <span className={t.write ? 'w' : 'r'}>{t.write ? 'W' : 'R'}</span>
                  {t.write && (
                    <span className={t.idempotent ? 'r' : 'w'}>
                      {t.idempotent ? '·幂等' : '·非幂等'}
                    </span>
                  )}
                </span>
              ))}
            </div>
            <p className="hint">W 写操作 · R 读操作。非幂等写操作的重试开关永远是关的。</p>
          </section>

          <section className="card">
            <h2>本次运行</h2>
            <div className="stats">
              <div className="stat">
                <div className="k">状态</div>
                <div className={`v ${result?.status === 'SUCCESS' ? 'ok' : result ? 'warn' : ''}`}>
                  {result?.status ?? (running ? '…' : '—')}
                </div>
              </div>
              <div className="stat">
                <div className="k">总耗时</div>
                <div className="v">{result ? `${result.wallMs}ms` : '—'}</div>
              </div>
              <div className="stat">
                <div className="k">调用</div>
                <div className="v">{result?.calls ?? lanes.length}</div>
              </div>
              <div className="stat">
                <div className="k">重试</div>
                <div className="v warn">{result?.retries ?? retries}</div>
              </div>
              <div className="stat">
                <div className="k">参数修复</div>
                <div className="v">{result?.repairs ?? repairs}</div>
              </div>
              <div className="stat">
                <div className="k">降级</div>
                <div className="v warn">{result?.fallbacks ?? degraded}</div>
              </div>
              <div className="stat">
                <div className="k">失败/跳过</div>
                <div className="v bad">{failed}</div>
              </div>
            </div>
            {result?.answer && <div className="answer">{result.answer}</div>}
            {error && (
              <p className="hint" style={{ color: '#9b2c2c' }}>
                {error}
              </p>
            )}
          </section>
        </div>

        <div>
          <section className="card">
            <h2>调用时间线(按依赖就绪并发)</h2>
            <Timeline lanes={lanes} total={total} />
            <div className="badges">
              <span className="badge ok">成功</span>
              <span className="badge retry">重试</span>
              <span className="badge repair">参数修复</span>
              <span className="badge fail">失败</span>
              <span className="badge">跳过</span>
            </div>
            {events.length > 0 && (
              <pre className="log">
                {events
                  .map((e) => `${String(e.t).padStart(5, ' ')}ms  ${e.type}`)
                  .join('\n')}
              </pre>
            )}
          </section>

          <section className="card">
            <h2>对照实验:L0 → L3</h2>
            <div className="row" style={{ marginTop: 0, marginBottom: 10 }}>
              <button className="ghost" disabled={benchLoading} onClick={doBench}>
                {benchLoading ? '评测中…' : '跑一次对照实验'}
              </button>
            </div>
            <BenchTable report={bench} loading={benchLoading} />
          </section>
        </div>
      </div>
    </div>
  )
}
