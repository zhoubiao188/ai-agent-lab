import type { Lane } from '../types'

interface Props {
  lanes: Lane[]
  total: number
}

function pctLeft(lane: Lane, total: number): number {
  return Math.min(99, (lane.start / Math.max(total, 1)) * 100)
}

function pctWidth(lane: Lane, total: number): number {
  const end = lane.end ?? total
  const w = ((end - lane.start) / Math.max(total, 1)) * 100
  return Math.max(1.5, Math.min(100, w))
}

export default function Timeline({ lanes, total }: Props) {
  if (lanes.length === 0) {
    return <p className="hint">还没有调用。点"运行"看一条真实的时间线。</p>
  }
  return (
    <div>
      {lanes.map((lane) => (
        <div className="lane" key={lane.id}>
          <div className="name" title={lane.id}>
            {lane.tool}
            {lane.dependsOn.length > 0 && (
              <span className="dep"> ← {lane.dependsOn.join(',')}</span>
            )}
          </div>
          <div className="track">
            <div
              className={`bar ${lane.status}`}
              style={{ left: `${pctLeft(lane, total)}%`, width: `${pctWidth(lane, total)}%` }}
              title={`${lane.tool} · ${lane.status}${
                lane.attempts > 1 ? ` · 尝试 ${lane.attempts} 次` : ''
              }${lane.error ? `\n${lane.error}` : ''}`}
            />
          </div>
          <div className="meta">
            {lane.end == null ? '…' : `${Math.round(lane.end - lane.start)}ms`}
          </div>
        </div>
      ))}
    </div>
  )
}
