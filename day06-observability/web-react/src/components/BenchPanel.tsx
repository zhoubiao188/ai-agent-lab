import { useState } from 'react'
import { api } from '../api'
import type { BenchReport } from '../types'

export default function BenchPanel() {
  const [report, setReport] = useState<BenchReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [incidents, setIncidents] = useState(200)

  const run = async () => {
    setLoading(true)
    setError('')
    try {
      setReport(await api.bench(incidents))
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  const pct = (v: number) => (v * 100).toFixed(0) + '%'

  return (
    <div>
      <div className="card">
        <div className="card-head">
          <h2>嫌疑排序评测</h2>
          <span className="hint">
            注入确定性故障,看排序能不能把真正的埋点排进前三。标准答案只有一份,算法拿不到。
          </span>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <label className="tiny muted">样本数</label>
          <input
            className="mono"
            type="number"
            min={5}
            max={1000}
            value={incidents}
            onChange={(e) => setIncidents(Number(e.target.value))}
            style={{ width: 80, padding: '4px 6px', border: '1px solid var(--border-strong)', borderRadius: 6 }}
          />
          <button className="chip primary" disabled={loading} onClick={run}>
            {loading ? ' 评测中' : '开始评测'}
          </button>
          {report && (
            <span className="tiny muted">
              seed {report.seed} · {report.generatedAt}
            </span>
          )}
        </div>
        {error && <p className="err">{error}</p>}
      </div>

      {report && (
        <>
          <div className="grid-4" style={{ marginTop: 12 }}>
            <div className="metric">
              <div className="k">Recall@1</div>
              <div className="v">{pct(report.overall.recall1)}</div>
              <div className="s">第一名就是根因</div>
            </div>
            <div className="metric">
              <div className="k">Recall@3</div>
              <div className="v">{pct(report.overall.recall3)}</div>
              <div className="s">前三名命中根因</div>
            </div>
            <div className="metric">
              <div className="k">平均名次</div>
              <div className="v">{report.overall.avgRank.toFixed(2)}</div>
              <div className="s">MRR {report.overall.mrr.toFixed(2)}</div>
            </div>
            <div className="metric">
              <div className="k">阅读量</div>
              <div className="v">{report.overall.avgSpans.toFixed(0)} → 3</div>
              <div className="s">平均 {report.overall.avgDecisions.toFixed(0)} 条决策</div>
            </div>
          </div>

          <div className="card" style={{ marginTop: 12 }}>
            <div className="card-head">
              <h2>分场景</h2>
              <span className="hint">{report.note}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>剧本</th>
                  <th className="num">样本</th>
                  <th className="num">R@1</th>
                  <th className="num">R@3</th>
                  <th className="num">MRR</th>
                  <th className="num">平均名次</th>
                  <th className="num">Span</th>
                  <th className="num">决策</th>
                  <th className="num">最高分</th>
                </tr>
              </thead>
              <tbody>
                {report.families.map((f) => (
                  <tr key={f.scenario}>
                    <td>
                      <strong>{f.label}</strong> <span className="muted tiny">{f.scenario}</span>
                    </td>
                    <td className="num">{f.incidents}</td>
                    <td className="num">{pct(f.recall1)}</td>
                    <td className="num">{pct(f.recall3)}</td>
                    <td className="num">{f.mrr.toFixed(2)}</td>
                    <td className="num">{f.avgRank.toFixed(1)}</td>
                    <td className="num">{f.avgSpans.toFixed(0)}</td>
                    <td className="num">{f.avgDecisions.toFixed(0)}</td>
                    <td className="num">{f.avgTopScore.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="card">
            <div className="card-head">
              <h2>与三个单点基线对比</h2>
              <span className="hint">只报"我们 92%"没有意义,必须给出朴素做法的成绩</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>做法</th>
                  <th>说明</th>
                  <th className="num">R@1</th>
                  <th className="num">R@3</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>
                    <strong>复合排序(本文)</strong>
                  </td>
                  <td className="muted tiny">耗时 + 错误 + 重试 + Token + 决策 + 完整性</td>
                  <td className="num">{pct(report.overall.recall1)}</td>
                  <td className="num">{pct(report.overall.recall3)}</td>
                </tr>
                {report.baselines.map((b) => (
                  <tr key={b.name}>
                    <td>{b.name}</td>
                    <td className="muted tiny">{b.description}</td>
                    <td className="num">{pct(b.recall1)}</td>
                    <td className="num">{pct(b.recall3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
