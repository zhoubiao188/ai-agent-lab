import type { BenchReport } from '../types'

interface Props {
  report: BenchReport | null
  loading: boolean
}

export default function BenchTable({ report, loading }: Props) {
  if (!report) {
    return (
      <p className="hint">
        {loading
          ? '正在跑评测:每组 120 个任务,共 4 组…'
          : '四组配置跑同一批任务、同一批故障序列,只换可靠性开关。大约 30 秒。'}
      </p>
    )
  }
  return (
    <>
      <table className="bench">
        <thead>
          <tr>
            <th>配置</th>
            <th>成功率</th>
            <th>降级</th>
            <th>失败</th>
            <th>均值</th>
            <th>P95</th>
            <th>重试</th>
            <th>修复</th>
          </tr>
        </thead>
        <tbody>
          {report.rows.map((row) => (
            <tr key={row.policy}>
              <td>{row.policy}</td>
              <td className="num">
                <div className="bar-cell">
                  <div className="fill" style={{ width: `${Math.round(row.successRate * 60)}px` }} />
                  <span>{(row.successRate * 100).toFixed(1)}%</span>
                </div>
              </td>
              <td className="num">{row.degraded}</td>
              <td className="num">{row.failed}</td>
              <td className="num">{row.avgMs}ms</td>
              <td className="num">{row.p95Ms}ms</td>
              <td className="num">{row.retries}</td>
              <td className="num">{row.repairs}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="hint">
        任务数 {report.tasks} · seed {report.seed} · 延迟压到{' '}
        {Math.round(report.latencyScale * 100)}% · 总耗时 {(report.totalMs / 1000).toFixed(1)}s
      </div>
    </>
  )
}
