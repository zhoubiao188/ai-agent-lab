import { useEffect, useMemo, useRef, useState } from 'react'
import { useAgentStream, type ChatMsg, type ToolRun } from './hooks/useAgentStream'
import { renderMarkdown } from './lib/markdown'

const PRESETS = [
  '北京今天天气怎么样?顺便帮我算一下 (128.5 + 71.5) * 3 / 2',
  '退货的运费谁出?发票抬头能改吗?',
  '现在几点了,距离今天结束还有多少小时',
  '你们的会员等级怎么升级,我要转人工投诉',
]

const ICON: Record<ToolRun['status'], string> = { running: '⏳', ok: '✅', error: '⚠️' }

function ToolPanel({ tools }: { tools: ToolRun[] }) {
  if (!tools.length) return null
  return (
    <div className="tool-panel">
      {tools.map((t) => (
        <div key={t.id} className={`tool-card ${t.status}`}>
          <div className="tool-head">
            <span className="tool-icon">{ICON[t.status]}</span>
            <code className="tool-name">{t.name}</code>
            {t.elapsedMs !== undefined && <span className="tool-time">{t.elapsedMs} ms</span>}
          </div>
          {(t.args || t.content) && (
            <details>
              <summary>参数与返回值</summary>
              {t.args && <pre className="tool-args">{prettyArgs(t.args)}</pre>}
              {t.content && (
                <div
                  className="tool-result"
                  dangerouslySetInnerHTML={{ __html: renderMarkdown(t.content) }}
                />
              )}
            </details>
          )}
        </div>
      ))}
    </div>
  )
}

function MessageBubble({ msg }: { msg: ChatMsg }) {
  const isAssistant = msg.role === 'assistant'
  const html = useMemo(() => renderMarkdown(msg.text), [msg.text])
  return (
    <div className={`msg ${msg.role}`}>
      <div className="avatar">{isAssistant ? 'AI' : 'ME'}</div>
      <div className="body">
        <ToolPanel tools={msg.tools} />
        {msg.text ? (
          <div className="bubble" dangerouslySetInnerHTML={{ __html: html }} />
        ) : msg.streaming ? (
          <div className="bubble typing">
            <span />
            <span />
            <span />
          </div>
        ) : null}
        {msg.error && <div className="error-bar">⚠️ {msg.error}</div>}
        {isAssistant && !msg.streaming && !!msg.steps && (
          <div className="meta">
            共 {msg.steps} 轮迭代
            {msg.tokens ? ` · 约 ${msg.tokens} tokens` : ''}
            {msg.elapsedMs !== undefined ? ` · ${(msg.elapsedMs / 1000).toFixed(1)}s` : ''}
          </div>
        )}
      </div>
    </div>
  )
}

export default function App() {
  const { messages, busy, info, infoError, send, abort, reset } = useAgentStream()
  const [input, setInput] = useState('')
  const scrollerRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const el = scrollerRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  const submit = () => {
    if (!input.trim() || busy) return
    const text = input
    setInput('')
    void send(text)
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">◈</span>
          <div>
            <h1>AI Agent 控制台</h1>
            <p>Spring Boot 3 · Spring Cloud Gateway · React 18 · SSE · Function Calling</p>
          </div>
        </div>
        <div className="status">
          {info && (
            <>
              <span className={`pill ${info.realModel ? 'live' : 'mock'}`}>
                {info.realModel ? '真实模型' : '离线演示'} · {info.provider}
              </span>
              <span className="pill ghost">{info.model}</span>
              <span className="pill ghost">{info.tools?.length ?? 0} 个工具</span>
            </>
          )}
          {!info && infoError && <span className="pill err">后端未连接</span>}
          <button className="link" onClick={reset}>
            清空会话
          </button>
        </div>
      </header>

      <main className="stream" ref={scrollerRef}>
        {messages.length === 0 && (
          <div className="welcome">
            <h2>手写一个会用工具的 Agent</h2>
            <p>整条链路都是真实运行的:LLM 流式输出 → 工具调用 → 结果回填 → 二次生成。</p>
            <div className="presets">
              {PRESETS.map((p) => (
                <button key={p} className="preset" onClick={() => void send(p)}>
                  {p}
                </button>
              ))}
            </div>
            {infoError && (
              <p className="hint">
                提示:先启动 <code>agent-service</code>(8081)与 <code>gateway</code>(8080),再刷新本页。
              </p>
            )}
          </div>
        )}
        {messages.map((m) => (
          <MessageBubble key={m.id} msg={m} />
        ))}
      </main>

      <footer className="composer">
        <textarea
          rows={1}
          value={input}
          placeholder="问点什么…(Enter 发送 / Shift+Enter 换行)"
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault()
              submit()
            }
          }}
        />
        {busy ? (
          <button className="send stop" onClick={abort}>
            停止
          </button>
        ) : (
          <button className="send" disabled={!input.trim()} onClick={submit}>
            发送
          </button>
        )}
      </footer>
    </div>
  )
}

function prettyArgs(args: string): string {
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}
