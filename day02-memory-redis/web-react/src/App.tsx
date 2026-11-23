import { useEffect, useRef, useState } from 'react'
import { ConversationList } from './components/ConversationList'
import { MemoryPanel } from './components/MemoryPanel'
import { MessageItem } from './components/MessageItem'
import { useAgent } from './hooks/useAgent'

export default function App() {
  const a = useAgent()
  const [input, setInput] = useState('')
  const listRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [a.messages, a.toast])

  const submit = () => {
    const text = input.trim()
    if (!text || a.sending) return
    setInput('')
    void a.send(text)
  }

  return (
    <div className="shell">
      <aside className="sidebar card">
        <div className="brand">
          <div className="logo">M</div>
          <div>
            <h1>长记忆 Agent</h1>
            <p>Redis · 滚动摘要 · 归档召回</p>
          </div>
        </div>
        <button className="new-btn" onClick={a.newConversation}>+ 新建对话</button>
        <ConversationList
          conversations={a.conversations}
          currentId={a.currentId}
          onSelect={(id) => void a.openConversation(id)}
          onRemove={(id) => void a.removeConversation(id)}
        />
        <div className="sidebar-foot">
          <span>存储:{a.stats?.store ?? '—'}</span>
          <span>会话数:{a.stats?.conversations ?? 0}</span>
          <span>摘要熔断:{a.stats?.summarizerBreakerOpen ? '已打开' : '正常'}</span>
        </div>
      </aside>

      <main className="main card">
        <header className="topbar">
          <div>
            <h2>{a.currentTitle}</h2>
            <div className="sub">
              {a.info?.provider ?? 'mock'} · {a.info?.model ?? '—'}
              {a.info && !a.info.realModel ? ' · 离线演示' : ''}
              {a.currentId ? ` · ${a.currentId.slice(0, 8)}` : ''}
            </div>
          </div>
          <div className="meter">
            <div className="meter-head">
              <span>记忆水位</span>
              <span>
                {a.memory?.tokensUsed ?? 0} / {a.memory?.budgetTokens ?? 6000}
              </span>
            </div>
            <div className="meter-bar">
              <div
                className={`meter-fill ${a.percent > 85 ? 'danger' : a.percent > 60 ? 'warn' : ''}`}
                style={{ width: `${a.percent}%` }}
              />
            </div>
          </div>
        </header>

        <section ref={listRef} className="messages">
          {!a.messages.length ? (
            <div className="empty">
              <h3>聊得久一点,看看它是怎么记住的</h3>
              <p>
                每一轮消息都会落库;上下文超过预算的 72% 时,最早的整轮对话会被折叠成摘要,原文进归档。
              </p>
              <ul>
                <li>
                  第 1 轮:<code>订单号 A20261123001 退款 899.00 元,承诺 2026-11-25 前到账,进度呢?</code>
                </li>
                <li>接着连发 6~10 轮,右侧面板会看到压缩事件与水位回落</li>
                <li>再问:<code>刚才那个订单的金额是多少?</code> —— 摘要与关键事实会兜住</li>
                <li>问一句 <code>帮我建个工单催财务</code> —— 观察工具卡片与两步编排</li>
              </ul>
            </div>
          ) : null}

          {a.messages.map((m) => (
            <MessageItem key={m.key} msg={m} />
          ))}

          {a.toast ? <div className="notice">{a.toast}</div> : null}
        </section>

        <footer className="composer">
          <div className="composer-box">
            <textarea
              rows={1}
              value={input}
              placeholder="说点什么…(Enter 发送 / Shift+Enter 换行)"
              disabled={a.sending}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  submit()
                }
              }}
            />
            <button className="send-btn" disabled={a.sending || !input.trim()} onClick={submit}>
              {a.sending ? '生成中' : '发送'}
            </button>
          </div>
          <div className="composer-hint">
            <span>同一会话串行处理:上一条没结束,新消息会收到 429。</span>
            <span>{a.messages.length} 条消息</span>
          </div>
        </footer>
      </main>

      <aside className="memory card">
        <div className="memory-head">
          <h3>记忆面板</h3>
          <span className={`pill${a.percent > 70 ? ' warn' : ''}`}>{a.percent}%</span>
        </div>
        <MemoryPanel
          memory={a.memory}
          recallHits={a.recallHits}
          archivedCount={a.archivedCount}
          compacting={a.compacting}
          percent={a.percent}
          onCompact={() => void a.compact()}
          onRefresh={() => void a.refreshMemory()}
        />
      </aside>
    </div>
  )
}
