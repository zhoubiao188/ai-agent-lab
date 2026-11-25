import { useCallback, useEffect, useState } from 'react'
import type { KbStats } from './types'
import RetrievePanel from './components/RetrievePanel'
import EvalPanel from './components/EvalPanel'
import AskPanel from './components/AskPanel'
import { getStats } from './api/client'

type Tab = 'retrieve' | 'eval' | 'ask'

const TABS: { key: Tab; label: string }[] = [
  { key: 'retrieve', label: '检索调试' },
  { key: 'eval', label: '评测看板' },
  { key: 'ask', label: '引用式问答' }
]

export default function App() {
  const [tab, setTab] = useState<Tab>('retrieve')
  const [stats, setStats] = useState<KbStats | null>(null)

  // 面板之间共享同一份语料统计,切换 tab 不该再打一次接口
  const reload = useCallback(async () => {
    try {
      setStats(await getStats())
    } catch {
      setStats(null)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  return (
    <div className="app">
      <div className="header">
        <div>
          <h1 className="title">RAG 检索控制台</h1>
          <p className="subtitle">
            分块 → 双路召回 → RRF 融合 → 特征重排 → 引用式生成,全链路可观测
          </p>
        </div>
        <span className="badge gray">React 18 + Vite</span>
      </div>

      <div className="metrics">
        <Metric k="文档 / 块" v={stats ? `${stats.docs} / ${stats.chunks}` : '—'} />
        <Metric k="倒排词表" v={stats?.vocab ?? '—'} />
        <Metric k="向量维度" v={stats?.dim ?? '—'} />
        <Metric k="向量索引" v={stats?.indexType ?? '—'} />
        <Metric k="建索引耗时" v={stats ? `${stats.buildMillis} ms` : '—'} />
        <Metric
          k="向量缓存命中率"
          v={stats ? `${(stats.cacheHitRate * 100).toFixed(0)}%` : '—'}
        />
      </div>

      <div className="tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            className={t.key === tab ? 'tab active' : 'tab'}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* key 让切换 tab 时组件重建,避免上一轮的请求把结果写进新面板 */}
      {tab === 'retrieve' && <RetrievePanel key="retrieve" />}
      {tab === 'eval' && <EvalPanel key="eval" />}
      {tab === 'ask' && <AskPanel key="ask" />}
    </div>
  )
}

function Metric({ k, v }: { k: string; v: string | number }) {
  return (
    <div className="metric">
      <div className="k">{k}</div>
      <div className="v">{v}</div>
    </div>
  )
}
