import { useCallback, useEffect, useState } from 'react'
import { getEvalReport } from '../api/client'
import type { EvalReport } from '../types'

function signed(v: number): string {
  return (v > 0 ? '+' : '') + v.toFixed(3)
}

function cls(v: number): string {
  return v > 0 ? 'delta up' : v < 0 ? 'delta down' : 'delta'
}

export default function EvalPanel() {
  const [report, setReport] = useState<EvalReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async (force = false) => {
    setLoading(true)
    setError('')
    try {
      setReport(await getEvalReport(force))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // Top-5 都没召回到的样本才是真正要修的问题;其他"没排第一"只是排序偏好
  const failures =
    report?.details
      .map((d) => ({ ...d, fullRank: d.ranks['FULL'] ?? -1 }))
      .filter((d) => d.fullRank < 0 || d.fullRank > 5) ?? []

  const types = report?.strategies[0] ? Object.keys(report.strategies[0].byType) : []

  return (
    <div className="panel">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div>
          <strong>召回消融实验</strong>
          <p className="muted" style={{ margin: '4px 0 0' }}>
            同一批样本、同一套语料,只换检索策略。改动任何参数之前先跑这一张表。
          </p>
        </div>
        <button className="ghost" disabled={loading} onClick={() => void load(true)}>
          {loading ? '评测中…' : '重跑评测'}
        </button>
      </div>
      {error && <p className="error">{error}</p>}

      {report ? (
        <>
          <p className="muted" style={{ marginTop: 12 }}>
            {report.docs} 篇文档 / {report.chunks} 块 / {report.cases} 条样本 · 向量模型{' '}
            {report.embeddingModel} · 索引 {report.indexType} · 生成于 {report.generatedAt}(
            {report.elapsedMs}ms)
          </p>

          <table>
            <thead>
              <tr>
                <th>策略</th>
                <th className="num">Recall@1</th>
                <th className="num">Recall@3</th>
                <th className="num">Recall@5</th>
                <th className="num">MRR</th>
                <th className="num">NDCG@5</th>
                <th className="num">P50</th>
                <th className="num">P95</th>
              </tr>
            </thead>
            <tbody>
              {report.strategies.map((s) => (
                <tr key={s.mode}>
                  <td>{s.label}</td>
                  <td className="num">{s.recall1.toFixed(3)}</td>
                  <td className="num">{s.recall3.toFixed(3)}</td>
                  <td className="num">{s.recall5.toFixed(3)}</td>
                  <td className="num">{s.mrr.toFixed(3)}</td>
                  <td className="num">{s.ndcg5.toFixed(3)}</td>
                  <td className="num">{s.p50Micros}μs</td>
                  <td className="num">{s.p95Micros}μs</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="spacer" />
          <strong style={{ fontSize: 13 }}>按查询类型拆解(Recall@5)</strong>
          <table>
            <thead>
              <tr>
                <th>策略</th>
                {types.map((t) => (
                  <th key={t} className="num">
                    {t}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {report.strategies.map((s) => (
                <tr key={s.mode}>
                  <td>{s.label}</td>
                  {types.map((t) => (
                    <td key={t} className="num">
                      {(s.byType[t]?.recall5 ?? 0).toFixed(2)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>

          <div className="spacer" />
          <strong style={{ fontSize: 13 }}>策略增量</strong>
          <table>
            <thead>
              <tr>
                <th>变化</th>
                <th className="num">Recall@5</th>
                <th className="num">MRR</th>
                <th className="num">NDCG@5</th>
              </tr>
            </thead>
            <tbody>
              {report.deltas.map((d) => (
                <tr key={d.from + d.to}>
                  <td>
                    {d.from} → {d.to}
                  </td>
                  <td className={cls(d.recall5)}>{signed(d.recall5)}</td>
                  <td className={cls(d.mrr)}>{signed(d.mrr)}</td>
                  <td className={cls(d.ndcg5)}>{signed(d.ndcg5)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="spacer" />
          <strong style={{ fontSize: 13 }}>失败样本(Top-5 未命中)</strong>
          {failures.length === 0 ? (
            <p className="muted">没有失败样本。这种情况通常说明评测集太简单,该加难度了。</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>类型</th>
                  <th>查询</th>
                  <th className="num">FULL 名次</th>
                </tr>
              </thead>
              <tbody>
                {failures.map((f) => (
                  <tr key={f.id}>
                    <td>{f.id}</td>
                    <td>{f.type}</td>
                    <td style={{ whiteSpace: 'normal' }}>{f.query}</td>
                    <td className="num">{f.fullRank > 0 ? f.fullRank : '未召回'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      ) : (
        !loading && <p className="empty">还没有评测数据。</p>
      )}
    </div>
  )
}
