import { useRef, useState } from 'react'
import { streamAsk } from '../api/client'
import type { Citation } from '../types'

export default function AskPanel() {
  const [question, setQuestion] = useState('Spring Boot 的三级缓存是怎么解决循环依赖的')
  const [answer, setAnswer] = useState('')
  const [citations, setCitations] = useState<Citation[]>([])
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState('')
  const [meta, setMeta] = useState('')

  // 用 ref 存 AbortController:它不参与渲染,放 state 只会多一次无意义的重建
  const controller = useRef<AbortController | null>(null)

  async function ask() {
    if (!question.trim() || streaming) return
    setStreaming(true)
    setAnswer('')
    setCitations([])
    setError('')
    setMeta('')
    controller.current = new AbortController()
    try {
      await streamAsk(
        question,
        'full',
        4,
        {
          onCitations: setCitations,
          // 必须用函数式更新:同一帧内多个 delta 会读到同一个旧 answer 而相互覆盖
          onDelta: (text) => setAnswer((prev) => prev + text),
          onDone: (p) => setMeta(`检索 ${p.retrieveMs}ms · 总计 ${p.totalMs}ms · ${p.model}`),
          onError: setError
        },
        controller.current.signal
      )
    } catch (e) {
      const err = e as Error
      // 用户主动中断不算错误
      if (err.name !== 'AbortError') {
        setError(err.message)
      }
    } finally {
      setStreaming(false)
      controller.current = null
    }
  }

  function stop() {
    controller.current?.abort()
    setStreaming(false)
  }

  return (
    <>
      <div className="panel">
        <div className="row">
          <input
            className="grow"
            type="text"
            value={question}
            placeholder="问一个知识库里应该有答案的问题"
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void ask()}
          />
          <button className="primary" disabled={streaming} onClick={() => void ask()}>
            {streaming ? '生成中…' : '提问'}
          </button>
          {streaming && (
            <button className="ghost" onClick={stop}>
              中断
            </button>
          )}
        </div>
        <p className="muted" style={{ margin: '8px 0 0' }}>
          先推引用、再推正文。引用一到位前端就能显示"正在查阅哪几篇文档",等待感直接减半。
        </p>
      </div>

      {citations.length > 0 && (
        <div className="panel">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <strong style={{ fontSize: 13 }}>引用片段</strong>
            <span className="muted">{meta}</span>
          </div>
          <div className="spacer" />
          {citations.map((c) => (
            <div key={c.chunkId} className="cite">
              <div className="row" style={{ gap: 6 }}>
                <span className="badge">[{c.index}]</span>
                <span className="doc">{c.title}</span>
                <span className="section">/ {c.section}</span>
                <span className="badge gray">{c.score}</span>
              </div>
              <p className="text">{c.snippet}</p>
            </div>
          ))}
        </div>
      )}

      {(answer || streaming || error) && (
        <div className="panel">
          <strong style={{ fontSize: 13 }}>回答</strong>
          {error && <p className="error">{error}</p>}
          <p className="answer" style={{ marginTop: 8 }}>
            {answer}
            {streaming && <span>▍</span>}
          </p>
        </div>
      )}
    </>
  )
}
