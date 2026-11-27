import { useState } from 'react'
import type { DecisionDto } from '../types'

interface Props {
  decisions: DecisionDto[]
  filter?: string
  onSelect: (spanId: string) => void
}

export default function DecisionLedger({ decisions, filter, onSelect }: Props) {
  const [onlyRisky, setOnlyRisky] = useState(false)

  const base = filter ? decisions.filter((d) => d.spanId === filter || d.code === filter) : decisions
  const list = onlyRisky ? base.filter((d) => d.confidence < 0.6 || d.margin < 0.1) : base

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
        <span className="tiny muted">共 {list.length} 条 · 每条都保留"被否掉的选项",否则台账就退化成日志</span>
        <label className="tiny muted" style={{ marginLeft: 'auto', cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={onlyRisky}
            onChange={(e) => setOnlyRisky(e.target.checked)}
            style={{ verticalAlign: -1 }}
          />
          只看可疑(置信度 &lt; 0.6 或差距 &lt; 0.1)
        </label>
      </div>
      {list.length === 0 && <div className="empty">没有符合条件的决策</div>}
      {list.map((d) => (
        <div
          key={d.code + d.seq}
          className="decision"
          style={{ cursor: 'pointer' }}
          onClick={() => onSelect(d.spanId)}
        >
          <div className="decision-top">
            <span className="decision-code">{d.code}</span>
            <span className="decision-title">{d.title}</span>
            <span className="badge ghost">{d.basisLabel}</span>
            <span className="mono tiny muted">置信度 {d.confidence.toFixed(2)}</span>
            <span className={d.confidence < 0.6 ? 'conf low' : 'conf'}>
              <i style={{ width: d.confidence * 100 + '%' }} />
            </span>
            {d.margin < 0.1 && <span className="badge warn">与次优几乎同分</span>}
          </div>
          <div className="decision-chosen">→ {d.chosen}</div>
          <div className="decision-sub">
            {d.question}
            {d.symptom ? ` · 出错时:${d.symptom}` : ''}
          </div>
          <div className="alts">
            {d.alternatives.map((a) => (
              <span key={a.name} className={'alt' + (a.name === d.chosen ? ' chosen' : '')} title={a.reason}>
                {a.name} · {a.score.toFixed(2)}
              </span>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
