import { SLOT_LABEL, type PlanStep, type StepStatus } from '../types'

interface Props {
  steps: PlanStep[]
  status: Record<string, StepStatus>
  durations: Record<string, number>
  errors: Record<string, string>
  violations: string[]
  missing: string[]
}

/**
 * 计划树。用纯函数从事件推出来的状态渲染 —— 刷新页面重放事件也能画出同一张图,
 * 这是"过程可回溯"的最小实现。
 */
export default function PlanTree({ steps, status, durations, errors, violations, missing }: Props) {
  return (
    <div>
      <div className="steps">
        {steps.map((s) => {
          const st = status[s.id] ?? 'idle'
          return (
            <div key={s.id} className={`step ${st}`}>
              <div className="row1">
                <span className="sid">{s.id}</span>
                <span className="title">{s.title}</span>
                <span className="tool">{s.tool}</span>
                {s.write && <span className="chip write">写</span>}
                <span className="ms">{st === 'ok' ? `${durations[s.id] ?? 0}ms` : st}</span>
              </div>
              <div className="row2">
                {s.dependsOn.map((d) => (
                  <span key={d} className="chip">← {d}</span>
                ))}
                {s.covers.map((c) => (
                  <span key={c} className="chip cover">{SLOT_LABEL[c] ?? c}</span>
                ))}
              </div>
              {errors[s.id] && <div className="err">{errors[s.id]}</div>}
            </div>
          )
        })}
      </div>

      {missing.length > 0 && (
        <div className="gaps">
          <div className="title">覆盖核对发现的缺口(金标准里有、计划里没有):</div>
          {missing.map((m) => (
            <span key={m} className="chip miss">{SLOT_LABEL[m] ?? m}</span>
          ))}
        </div>
      )}

      {violations.length > 0 && (
        <div className="gaps">
          <div className="title">静态校验不通过:</div>
          {violations.map((v) => (
            <div key={v} className="err">{v}</div>
          ))}
        </div>
      )}
    </div>
  )
}
