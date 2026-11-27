import type { SpanDto, SuspectDto } from '../types'

const KIND_COLOR: Record<string, string> = {
  RUN: '#667085',
  STEP: '#7f56d9',
  LLM: '#0ba5ec',
  TOOL: '#12b76a',
  WRITE: '#f79009',
  RETRY: '#f04438',
  DECISION: '#98a2b3',
  VALIDATE: '#2e90fa',
  INTERNAL: '#98a2b3'
}

interface Props {
  spans: SpanDto[]
  totalMs: number
  suspects: SuspectDto[]
  selectedId: string
  onSelect: (span: SpanDto) => void
}

function fmt(ms: number): string {
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms.toFixed(ms < 10 ? 2 : 0) + 'ms'
}

export default function Waterfall({ spans, totalMs, suspects, selectedId, onSelect }: Props) {
  const byId = new Map(spans.map((s) => [s.id, s]))
  const suspectIds = new Set(suspects.map((s) => s.spanId))
  const total = Math.max(totalMs, 1)

  const depthOf = (span: SpanDto): number => {
    let depth = 0
    let parent = span.parentId ? byId.get(span.parentId) : undefined
    while (parent) {
      depth += 1
      parent = parent.parentId ? byId.get(parent.parentId) : undefined
    }
    return depth
  }

  return (
    <div>
      <div className="wf-head waterfall">
        <span>Span(按父子缩进)</span>
        <span>时间轴 · 全程 {fmt(totalMs)}</span>
        <span style={{ textAlign: 'right' }}>耗时</span>
      </div>
      <div className="waterfall">
        {spans.map((span) => {
          const depth = depthOf(span)
          const suspect = suspectIds.has(span.id)
          const left = Math.max(0, Math.min(100, (span.startMs / total) * 100))
          const width = Math.max(0.4, Math.min(100, (span.durationMs / total) * 100))
          return (
            <div
              key={span.id}
              className={
                'wf-row' + (span.id === selectedId ? ' selected' : '') + (span.status === 'ERROR' ? ' is-error' : '')
              }
              onClick={() => onSelect(span)}
            >
              <div className="wf-name" style={{ paddingLeft: depth * 10 + 'px' }}>
                <span className="kind-dot" style={{ background: KIND_COLOR[span.kind] ?? '#98a2b3' }} />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{span.name}</span>
                {span.attempts > 1 && <span className="badge warn" style={{ padding: '0 4px' }}>×{span.attempts}</span>}
                {!span.integrity && <span className="badge bad" style={{ padding: '0 4px' }}>完整性</span>}
                {suspect && <span className="badge bad" style={{ padding: '0 4px' }}>嫌疑</span>}
              </div>
              <div className="wf-track">
                <div
                  className={'wf-bar' + (suspect ? ' suspect' : '') + (span.durationMs < 0.5 ? ' point' : '')}
                  style={{
                    left: left + '%',
                    width: span.durationMs < 0.5 ? 3 : width + '%',
                    background: KIND_COLOR[span.kind] ?? '#98a2b3'
                  }}
                  title={`${span.name} · ${fmt(span.durationMs)} · self ${fmt(span.selfMs)}`}
                />
              </div>
              <div className="wf-dur">{fmt(span.durationMs)}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
