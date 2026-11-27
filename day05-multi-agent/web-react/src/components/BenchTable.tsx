import type { BenchReport } from '../types'

function pct(v: number) {
  return (v * 100).toFixed(1) + '%'
}

export default function BenchTable({ report }: { report: BenchReport | null }) {
  if (!report) {
    return <div className="hint">还没有跑对照实验。点上面的「跑四组对照」,20 个任务 × 4 组配置大约 30 秒。</div>
  }
  return (
    <div>
      <table>
        <thead>
          <tr>
            <th>配置</th>
            <th>成功率</th>
            <th>假成功</th>
            <th>被拦下</th>
            <th>平均步数</th>
            <th>平均延迟</th>
            <th>P95</th>
            <th>重规划</th>
          </tr>
        </thead>
        <tbody>
          {report.rows.map((r, i) => (
            <tr key={r.policy} className={i === report.rows.length - 1 ? 'best' : ''}>
              <td>{r.policy}</td>
              <td className="num">{r.success}/{r.total} ({pct(r.successRate)})</td>
              <td className="num">{r.falseSuccess}</td>
              <td className="num">{r.blocked}</td>
              <td className="num">{r.avgSteps}</td>
              <td className="num">{r.avgMs}ms</td>
              <td className="num">{r.p95Ms}ms</td>
              <td className="num">{r.replans}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="detail">
        <table>
          <thead>
            <tr>
              <th>任务</th>
              <th>缺陷</th>
              {report.rows.map((r) => (
                <th key={r.policy}>{r.policy.split('-')[0]}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.details.map((d) => (
              <tr key={d.taskId}>
                <td className="tag">{d.taskId}</td>
                <td className="tag">{d.flaw === 'CLEAN' ? '—' : d.flaw}</td>
                {report.rows.map((r) => (
                  <td key={r.policy} className="tag">
                    {d.byPolicy[r.policy] === 'OK' ? '✓' : '✗'}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
