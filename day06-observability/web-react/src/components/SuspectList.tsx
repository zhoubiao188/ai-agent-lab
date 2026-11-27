import type { SuspectDto } from '../types'

const SEGMENTS: { key: string; label: string; color: string }[] = [
  { key: 'selfMs', label: '耗时', color: '#7f56d9' },
  { key: 'error', label: '错误', color: '#f04438' },
  { key: 'retry', label: '重试', color: '#f79009' },
  { key: 'tokens', label: 'Token', color: '#0ba5ec' },
  { key: 'decision', label: '决策', color: '#ee46bc' },
  { key: 'integrity', label: '完整性', color: '#12b76a' }
]

interface Props {
  suspects: SuspectDto[]
  narrative: string
  onSelect: (spanId: string) => void
}

export default function SuspectList({ suspects, narrative, onSelect }: Props) {
  return (
    <div>
      <div className="suspects">
        {suspects.map((s, i) => (
          <div key={s.spanId} className="suspect" onClick={() => onSelect(s.spanId)}>
            <div className="suspect-rank">
              #{i + 1} · {s.kindLabel}
              {s.decisionCode ? ` · ${s.decisionCode}` : ''}
            </div>
            <div className="suspect-name">{s.name}</div>
            <div className="suspect-score">
              {s.score.toFixed(3)}
              <small>分</small>
            </div>
            <div className="stack">
              {SEGMENTS.map((seg) => (
                <i
                  key={seg.key}
                  style={{
                    width: Math.round((s.contributions[seg.key] ?? 0) * 100) + '%',
                    background: seg.color
                  }}
                  title={`${seg.label} ${Math.round((s.contributions[seg.key] ?? 0) * 100)}%`}
                />
              ))}
            </div>
            <ul className="evidence">
              {s.evidence.map((e, idx) => (
                <li key={idx}>{e}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      {narrative ? <div className="narrative">{narrative}</div> : null}
    </div>
  )
}
