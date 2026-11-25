import { useState } from 'react'
import HitCard from './HitCard'
import { retrieve } from '../api/client'
import type { Mode, RetrieveResponse } from '../types'

const MODES: { value: Mode; label: string }[] = [
  { value: 'bm25', label: '① BM25' },
  { value: 'vector', label: '② 向量' },
  { value: 'hybrid', label: '③ 混合 RRF' },
  { value: 'full', label: '④ 混合+重排' }
]

const EXAMPLES = [
  '网关流式输出为什么会被缓冲',
  '虚拟线程什么时候反而更慢',
  'limit 翻到第一百万行很慢怎么办',
  'RRF 为什么比加权分数稳定',
  '同一句话问四种模式看排序差异'
]

export default function RetrievePanel() {
  const [query, setQuery] = useState('网关流式输出为什么会被缓冲')
  const [mode, setMode] = useState<Mode>('full')
  const [topK, setTopK] = useState(5)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<RetrieveResponse | null>(null)

  async function run(q = query) {
    if (!q.trim() || loading) return
    setLoading(true)
    setError('')
    try {
      setResult(await retrieve(q, mode, topK))
    } catch (e) {
      setError((e as Error).message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  function pick(text: string) {
    setQuery(text)
    void run(text)
  }

  return (
    <>
      <div className="panel">
        <div className="row">
          <input
            className="grow"
            type="text"
            value={query}
            placeholder="输入一个真实用户会问的问题,比如:网关超时怎么配"
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void run()}
          />
          <select value={mode} onChange={(e) => setMode(e.target.value as Mode)}>
            {MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
          <select value={topK} onChange={(e) => setTopK(Number(e.target.value))}>
            {[3, 5, 8, 12].map((n) => (
              <option key={n} value={n}>
                Top {n}
              </option>
            ))}
          </select>
          <button className="primary" disabled={loading} onClick={() => void run()}>
            {loading ? '检索中…' : '检索'}
          </button>
        </div>
        <div className="examples">
          {EXAMPLES.map((e) => (
            <button key={e} className="example" onClick={() => pick(e)}>
              {e}
            </button>
          ))}
        </div>
        {error && <p className="error">{error}</p>}
      </div>

      {result ? (
        <div className="panel">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              <span className="badge">{result.mode}</span>
              <span className="muted" style={{ marginLeft: 8 }}>
                {result.total} 条命中 · 耗时 {result.tookMs}ms
              </span>
            </div>
            <span className="muted">同一张卡片上的四路分数,就是这条结果被排上来的全部依据</span>
          </div>
          {result.hits.map((hit, i) => (
            <HitCard key={hit.chunk.id} hit={hit} rank={i + 1} />
          ))}
          {result.hits.length === 0 && (
            <p className="empty">没有命中。换个问法,或检查知识库里是否真有相关内容。</p>
          )}
        </div>
      ) : (
        <div className="panel empty">输入问题后点检索,先看召回,再谈生成。</div>
      )}
    </>
  )
}
