import { useEffect, useMemo, useState } from 'react'
import { fetchPolicies, fetchTasks, runBench, streamRun } from './api'
import type { AgentEvent, BenchReport, PlanStep, PolicyItem, StepStatus, TaskItem } from './types'
import PlanTree from './components/PlanTree'
import BenchTable from './components/BenchTable'

interface LogLine {
  t: number
  text: string
  tone: string
}

interface RunState {
  steps: PlanStep[]
  status: Record<string, StepStatus>
  durations: Record<string, number>
  errors: Record<string, string>
  violations: string[]
  missing: string[]
  verdict: string
  flawLabel: string
  answer: any
  logs: LogLine[]
}

const EMPTY: RunState = {
  steps: [],
  status: {},
  durations: {},
  errors: {},
  violations: [],
  missing: [],
  verdict: '',
  flawLabel: '',
  answer: null,
  logs: []
}

/**
 * 事件 → 状态,写成纯函数。
 * 好处是刷新页面后把事件重放一遍就能还原整块面板,不需要服务端额外存快照。
 */
function applyEvent(prev: RunState, e: AgentEvent): RunState {
  const d = e.data
  const log = (text: string, tone = ''): LogLine[] => [...prev.logs, { t: e.t, text, tone }]

  switch (e.type) {
    case 'task':
      return { ...prev, logs: log(`${d.taskId} · ${d.policy}`) }
    case 'plan':
      return { ...prev, steps: d.steps, logs: log(`生成计划:${d.count} 步`) }
    case 'plan_flaw':
      return { ...prev, flawLabel: d.label, logs: log(`计划缺陷:${d.label} —— ${d.symptom}`, 'bad') }
    case 'validate_ok':
      return { ...prev, logs: log(`静态校验通过(${d.steps} 步)`, 'ok') }
    case 'validate_fail':
      return { ...prev, violations: d.violations, logs: log(`静态校验不通过:${d.violations.length} 条`, 'bad') }
    case 'coverage':
      return {
        ...prev,
        missing: d.missing,
        logs: log(`覆盖核对:${d.covered.length}/${d.required.length}`, d.missing.length ? 'warn' : 'ok')
      }
    case 'coverage_patch':
      return { ...prev, logs: log(`机械补步:补上 ${d.added.join(',')}`, 'warn') }
    case 'repair_patch':
      return { ...prev, logs: log(`复核建议补步,已补到 ${d.steps} 步`, 'warn') }
    case 'step_start':
      return {
        ...prev,
        status: { ...prev.status, [d.stepId]: 'running' },
        logs: log(`▶ ${d.stepId} ${d.tool}`)
      }
    case 'step_ok':
      return {
        ...prev,
        status: { ...prev.status, [d.stepId]: 'ok' },
        durations: { ...prev.durations, [d.stepId]: d.ms },
        logs: log(`✓ ${d.stepId} ${d.tool} ${d.ms}ms`, 'ok')
      }
    case 'step_fail':
      return {
        ...prev,
        status: { ...prev.status, [d.stepId]: 'fail' },
        errors: { ...prev.errors, [d.stepId]: d.error },
        logs: log(`✗ ${d.stepId} ${d.tool}: ${d.error}`, 'bad')
      }
    case 'step_skip':
      return {
        ...prev,
        status: { ...prev.status, [d.stepId]: 'skip' },
        logs: log(`↷ ${d.stepId} 跳过:${d.reason}`, 'warn')
      }
    case 'step_cached':
      return {
        ...prev,
        status: { ...prev.status, [d.stepId]: 'cached' },
        logs: log(`↺ ${d.stepId} 复用上次结果`, 'warn')
      }
    case 'critic':
      return {
        ...prev,
        verdict: d.verdict,
        logs: log(`复核结论 ${d.verdict}${d.findings.length ? ':' + d.findings.join(';') : ''}`,
          d.verdict === 'PASS' ? 'ok' : 'warn')
      }
    case 'replan':
      return { ...prev, logs: log(`重规划 #${d.round}:${d.reason}`, 'warn') }
    case 'replan_abort':
      return { ...prev, logs: log(`放弃重规划:${d.reason}`, 'warn') }
    case 'answer':
      return { ...prev, answer: d, logs: log(`${d.success ? '交付' : '未完成'}:${d.text}`, d.success ? 'ok' : 'bad') }
    case 'done':
      return {
        ...prev,
        logs: log(`结束 ${d.durationMs}ms,${d.steps} 步,重规划 ${d.replans} 次`, d.success ? 'ok' : 'bad')
      }
    case 'error':
      return { ...prev, logs: log(`服务端错误:${d.message}`, 'bad') }
    default:
      return prev
  }
}

export default function App() {
  const [tasks, setTasks] = useState<TaskItem[]>([])
  const [policies, setPolicies] = useState<PolicyItem[]>([])
  const [current, setCurrent] = useState('T03')
  const [policy, setPolicy] = useState('C3-复核重规划')
  const [flaw, setFlaw] = useState('AUTO')
  const [running, setRunning] = useState(false)
  const [state, setState] = useState<RunState>(EMPTY)
  const [report, setReport] = useState<BenchReport | null>(null)
  const [benchLoading, setBenchLoading] = useState(false)

  useEffect(() => {
    fetchTasks().then(setTasks)
    fetchPolicies().then(setPolicies)
  }, [])

  const currentTask = useMemo(() => tasks.find((t) => t.id === current), [tasks, current])

  async function run() {
    if (running) return
    setState(EMPTY)
    setRunning(true)
    try {
      await streamRun({ taskId: current, policy, flaw }, (e) => setState((prev) => applyEvent(prev, e)))
    } catch (err: any) {
      setState((prev) => ({ ...prev, logs: [...prev.logs, { t: 0, text: String(err.message ?? err), tone: 'bad' }] }))
    } finally {
      setRunning(false)
    }
  }

  async function bench(limit = 0) {
    if (benchLoading) return
    setBenchLoading(true)
    try {
      setReport(await runBench(limit))
    } finally {
      setBenchLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="top">
        <h1>多 Agent 协作 · 计划错了怎么发现</h1>
        <span className="sub">Planner / Executor / Critic + 四组防线对照</span>
      </header>

      <div className="layout">
        <div>
          <div className="panel">
            <h2>任务</h2>
            <div className="task-list">
              {tasks.map((t) => (
                <div key={t.id} className={`task-item${t.id === current ? ' active' : ''}`}
                     onClick={() => setCurrent(t.id)}>
                  <span className="tid">{t.id}</span>{t.request}
                  {t.flaw !== 'CLEAN' && <span className="flaw">·{t.flaw}</span>}
                </div>
              ))}
            </div>
          </div>

          <div className="split" />

          <div className="panel">
            <h2>运行</h2>
            <label className="field">
              <span>防线配置</span>
              <select value={policy} onChange={(e) => setPolicy(e.target.value)}>
                {policies.map((p) => (
                  <option key={p.name} value={p.name}>{p.name}</option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>计划缺陷</span>
              <select value={flaw} onChange={(e) => setFlaw(e.target.value)}>
                <option value="AUTO">用任务自带的</option>
                <option value="NONE">不注入(干净计划)</option>
                <option value="UNDER_COVER">拆漏</option>
                <option value="WRONG_ORDER">拆错(金额写死)</option>
                <option value="OVER_SPLIT">拆多(重复调用)</option>
              </select>
            </label>
            <button className="primary" disabled={running} onClick={run}>
              {running ? '运行中…' : '运行这个任务'}
            </button>
            <div className="hint">{currentTask?.request}</div>
          </div>
        </div>

        <div>
          <div className="panel">
            <h2>计划与执行</h2>
            <div className="badges">
              <span className="badge">策略 {policy}</span>
              {state.flawLabel && <span className="badge bad">缺陷 {state.flawLabel}</span>}
              {state.verdict && <span className="badge ok">复核 {state.verdict}</span>}
              {state.answer?.success && <span className="badge ok">金标准通过</span>}
              {state.answer && !state.answer.success && <span className="badge bad">金标准不通过</span>}
              {state.answer?.claimed && !state.answer.success && <span className="badge warn">假成功</span>}
            </div>

            <PlanTree steps={state.steps} status={state.status} durations={state.durations}
                      errors={state.errors} violations={state.violations} missing={state.missing} />

            {state.answer && (
              <div className={`answer ${state.answer.success ? 'ok' : 'bad'}`}>
                <div><span className="k">{state.answer.success ? '交付' : '未完成'}</span>: {state.answer.text}</div>
                {!state.answer.success && (
                  <div className="hint">原因:{(state.answer.reasons || []).join(';')}</div>
                )}
              </div>
            )}
          </div>

          <div className="split" />

          <div className="panel">
            <h2>事件流</h2>
            <div className="log">
              {state.logs.map((l, i) => (
                <div key={i} className={`line ${l.tone}`}>
                  <span className="t">{l.t}ms</span>{l.text}
                </div>
              ))}
              {state.logs.length === 0 && <div className="hint">选一个任务,点「运行这个任务」。</div>}
            </div>
          </div>
        </div>
      </div>

      <div className="split" />

      <div className="panel">
        <h2>四组对照</h2>
        <div className="badges">
          <button className="ghost" style={{ width: 'auto' }} disabled={benchLoading} onClick={() => bench(0)}>
            {benchLoading ? '跑着…' : '跑四组对照(20 个任务)'}
          </button>
          <button className="ghost" style={{ width: 'auto' }} disabled={benchLoading} onClick={() => bench(6)}>
            只跑前 6 个
          </button>
          {report && <span className="hint">seed {report.seed},{report.taskCount} 个任务</span>}
        </div>
        <BenchTable report={report} />
      </div>
    </div>
  )
}
