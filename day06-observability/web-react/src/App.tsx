import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './api'
import BenchPanel from './components/BenchPanel'
import DecisionLedger from './components/DecisionLedger'
import SuspectList from './components/SuspectList'
import Waterfall from './components/Waterfall'
import type { Catalog, Overview, RunSummary, SpanDto, TraceDetail } from './types'

type Tab = 'locate' | 'ledger' | 'bench'

function fmtMs(ms: number): string {
  return ms >= 1000 ? (ms / 1000).toFixed(2) + ' s' : ms.toFixed(1) + ' ms'
}

function fmtCost(micros: number): string {
  return (micros / 1_000_000).toFixed(4) + ' 元'
}

function outcomeClass(outcome: string): string {
  if (outcome === 'SUCCESS') return 'badge ok'
  if (outcome === 'SILENT_FAILURE') return 'badge warn'
  return 'badge bad'
}

export default function App() {
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [trace, setTrace] = useState<TraceDetail | null>(null)
  const [overview, setOverview] = useState<Overview | null>(null)
  const [tab, setTab] = useState<Tab>('locate')
  const [selectedSpanId, setSelectedSpanId] = useState('')
  const [error, setError] = useState('')
  const [running, setRunning] = useState('')

  const openTrace = useCallback(async (id: string) => {
    setError('')
    try {
      const detail = await api.trace(id)
      setTrace(detail)
      setSelectedSpanId(detail.suspects[0]?.spanId ?? detail.spans[0]?.id ?? '')
    } catch (e) {
      setError(String(e))
    }
  }, [])

  const refresh = useCallback(async () => {
    setRuns(await api.runs(30))
    setOverview(await api.overview())
  }, [])

  useEffect(() => {
    ;(async () => {
      try {
        setCatalog(await api.catalog())
        await refresh()
        const list = await api.runs(30)
        if (list.length > 0) await openTrace(list[0].traceId)
      } catch (e) {
        setError(String(e) + ' —— 确认 8081 服务已启动')
      }
    })()
  }, [openTrace, refresh])

  const runScenario = async (scenario: string, question: string) => {
    setRunning(scenario)
    setError('')
    try {
      const res = await api.run(question, scenario)
      await refresh()
      await openTrace(res.traceId)
      setTab('locate')
    } catch (e) {
      setError(String(e))
    } finally {
      setRunning('')
    }
  }

  const selectedSpan: SpanDto | undefined = useMemo(
    () => trace?.spans.find((s) => s.id === selectedSpanId),
    [trace, selectedSpanId]
  )
  const decisionsForSelected = useMemo(
    () => (trace ? trace.decisions.filter((d) => d.spanId === selectedSpanId) : []),
    [trace, selectedSpanId]
  )

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <h1>Agent 可观测台 · Day06</h1>
          <span>20 个决策点 · 三屏定位 · 嫌疑排序(200 条样本 R@3 100%)</span>
        </div>
        <div className="scenario-bar">
          {(catalog?.scenarios ?? []).map((s) => (
            <button
              key={s.name}
              className={'chip' + (s.name === 'NORMAL' ? ' primary' : '')}
              disabled={running !== ''}
              title={s.description}
              onClick={() =>
                runScenario(
                  s.name,
                  catalog?.tasks.find((t) => t.scenario === s.name)?.question ??
                    '帮我把订单 ORD-100234 的 299 元退款处理一下'
                )
              }
            >
              {s.label}
            </button>
          ))}
        </div>
      </header>

      <div className="body">
        <aside className="sidebar">
          <div className="sidebar-head">
            <span>最近运行</span>
            <span className="mono">{runs.length}</span>
          </div>
          <div className="run-list">
            {runs.map((r) => (
              <div
                key={r.traceId}
                className={'run-item' + (r.traceId === trace?.traceId ? ' active' : '')}
                onClick={() => openTrace(r.traceId)}
              >
                <div className="run-q">
                  {r.scenarioLabel} · {r.topSuspect}
                </div>
                <div className="run-line">
                  <span className={outcomeClass(r.outcome)}>{r.outcomeLabel}</span>
                  {r.silent && <span className="badge warn">沉默</span>}
                  <span className="run-meta">
                    {fmtMs(r.durationMs)} · {r.tokens}tok
                  </span>
                </div>
              </div>
            ))}
            {runs.length === 0 && <div className="empty">还没有运行记录</div>}
          </div>
          {overview && (
            <>
              <div className="sidebar-head" style={{ borderTop: '1px solid var(--border)' }}>
                <span>整体口径</span>
              </div>
              <div style={{ padding: '0 14px 14px', fontSize: 11.5 }} className="mono muted">
                <div>
                  成功率 {(overview.successRate * 100).toFixed(0)}% · 沉默{' '}
                  {(overview.silentRate * 100).toFixed(0)}%
                </div>
                <div>
                  P50 {overview.p50Ms.toFixed(0)}ms · P95 {overview.p95Ms.toFixed(0)}ms
                </div>
                <div>
                  重试放大 {overview.avgRetryAmp.toFixed(2)}× · {overview.avgSpans.toFixed(0)} span
                </div>
              </div>
            </>
          )}
        </aside>

        <main className="main">
          {error && (
            <p className="err" style={{ marginBottom: 10 }}>
              {error}
            </p>
          )}

          <div className="tabs">
            <button className={'tab' + (tab === 'locate' ? ' active' : '')} onClick={() => setTab('locate')}>
              三屏定位
            </button>
            <button className={'tab' + (tab === 'ledger' ? ' active' : '')} onClick={() => setTab('ledger')}>
              决策台账 <span className="muted">({trace?.decisionCount ?? 0})</span>
            </button>
            <button className={'tab' + (tab === 'bench' ? ' active' : '')} onClick={() => setTab('bench')}>
              评测
            </button>
          </div>

          {tab === 'locate' &&
            (!trace ? (
              <div className="card empty">左侧选一次运行,或点右上角任意剧本跑一次</div>
            ) : (
              <>
                <div className="grid-4">
                  <div className="metric">
                    <div className="k">结论 / 自检可信度</div>
                    <div className={'v' + (trace.outcome !== 'SUCCESS' ? ' bad' : '')}>{trace.outcomeLabel}</div>
                    <div className="s">
                      trust {trace.trust.toFixed(2)} · 重试放大 {trace.retryAmplification.toFixed(1)}×
                    </div>
                  </div>
                  <div className="metric">
                    <div className="k">全程耗时</div>
                    <div className="v">{fmtMs(trace.durationMs)}</div>
                    <div className="s">
                      {trace.spanCount} 个 Span · {trace.decisionCount} 条决策
                    </div>
                  </div>
                  <div className="metric">
                    <div className="k">Token / 成本</div>
                    <div className="v">{trace.tokens}</div>
                    <div className="s">{fmtCost(trace.costMicros)}</div>
                  </div>
                  <div className={'metric' + (trace.sampled ? '' : ' warn')}>
                    <div className="k">采样</div>
                    <div className="v">{trace.sampled ? '已上报' : '仅本地'}</div>
                    <div className="s">失败与低可信度必定上报</div>
                  </div>
                </div>

                <div className="card" style={{ marginTop: 12 }}>
                  <div className="card-head">
                    <h2>第一屏 · 三个嫌疑</h2>
                    <span className="hint">
                      从 {trace.spanCount} 个 Span 里挑出最该看的 3 个,每人一句话证据
                    </span>
                  </div>
                  <SuspectList
                    suspects={trace.suspects}
                    narrative={trace.narrative}
                    onSelect={setSelectedSpanId}
                  />
                </div>

                <div className="card">
                  <div className="card-head">
                    <h2>第二屏 · 瀑布图</h2>
                    <span className="hint">
                      红框 = 嫌疑;×n = 尝试次数;完整性标记表示"没有 error 但结果不对"
                    </span>
                  </div>
                  <Waterfall
                    spans={trace.spans}
                    totalMs={trace.durationMs}
                    suspects={trace.suspects}
                    selectedId={selectedSpanId}
                    onSelect={(s) => setSelectedSpanId(s.id)}
                  />
                </div>

                <div className="card">
                  <div className="card-head">
                    <h2>第三屏 · 这个 Span 凭什么</h2>
                    <span className="hint">属性 + 事件 + 它自己的决策;三者对不上号时,问题就在这里</span>
                  </div>
                  {!selectedSpan ? (
                    <div className="empty">点瀑布图上任意一行</div>
                  ) : (
                    <>
                      <div
                        style={{
                          display: 'flex',
                          gap: 8,
                          alignItems: 'center',
                          flexWrap: 'wrap',
                          marginBottom: 8
                        }}
                      >
                        <strong className="mono">{selectedSpan.name}</strong>
                        <span className="badge ghost">{selectedSpan.kindLabel}</span>
                        <span className={selectedSpan.status === 'ERROR' ? 'badge bad' : 'badge ok'}>
                          {selectedSpan.status}
                        </span>
                        <span className="mono tiny muted">
                          {fmtMs(selectedSpan.durationMs)} · self {fmtMs(selectedSpan.selfMs)}
                          {selectedSpan.attempts > 1 ? ` · 尝试 ${selectedSpan.attempts} 次` : ''}
                        </span>
                        {!selectedSpan.integrity && <span className="badge bad">完整性未通过</span>}
                        {selectedSpan.redactions > 0 && (
                          <span className="badge info">脱敏 {selectedSpan.redactions} 处</span>
                        )}
                      </div>
                      {selectedSpan.errorMessage && (
                        <p className="err mono tiny">
                          {selectedSpan.errorKind}: {selectedSpan.errorMessage}
                        </p>
                      )}
                      <dl className="kv">
                        {Object.entries(selectedSpan.attrs).map(([k, v]) => (
                          <div key={k} style={{ display: 'contents' }}>
                            <dt>{k}</dt>
                            <dd>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
                          </div>
                        ))}
                      </dl>
                      {selectedSpan.events.length > 0 && (
                        <div style={{ marginTop: 8 }}>
                          <div className="tiny muted" style={{ marginBottom: 2 }}>
                            事件
                          </div>
                          {selectedSpan.events.map((e, i) => (
                            <div key={i} className="mono tiny">
                              +{e.atMs}ms {e.name} {JSON.stringify(e.attrs)}
                            </div>
                          ))}
                        </div>
                      )}
                      {decisionsForSelected.length > 0 && (
                        <div style={{ marginTop: 10 }}>
                          <div className="tiny muted" style={{ marginBottom: 4 }}>
                            该 Span 上的决策
                          </div>
                          <DecisionLedger decisions={decisionsForSelected} onSelect={setSelectedSpanId} />
                        </div>
                      )}
                    </>
                  )}
                </div>
              </>
            ))}

          {tab === 'ledger' && (
            <>
              <div className="card">
                <div className="card-head">
                  <h2>决策台账 · {trace?.question ?? ''}</h2>
                  <span className="hint">20 个决策点的全集,不是每次都会走满</span>
                </div>
                {trace ? (
                  <DecisionLedger decisions={trace.decisions} onSelect={setSelectedSpanId} />
                ) : (
                  <div className="empty">先选一次运行</div>
                )}
              </div>
              <div className="card">
                <div className="card-head">
                  <h2>回答</h2>
                  <span className="hint">用户最终看到的内容</span>
                </div>
                <p style={{ margin: 0, fontSize: 12.5 }}>{trace?.answer}</p>
              </div>
            </>
          )}

          {tab === 'bench' && <BenchPanel />}
        </main>
      </div>
    </div>
  )
}
