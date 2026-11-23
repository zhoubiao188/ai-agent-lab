import { renderMarkdown } from '../lib/markdown'
import type { UiMessage } from '../types'

/** 单条消息:用户气泡 / AI 气泡 / 工具卡片 / 系统提示 */
export function MessageItem({ msg }: { msg: UiMessage }) {
  if (msg.role === 'tool') {
    return (
      <div className={`tool-card${msg.success === false ? ' failed' : ''}`}>
        <div className="tool-head">
          <span>⚙</span>
          <span>{msg.toolName || 'tool'}</span>
          {msg.elapsedMs ? (
            <span style={{ color: '#9aa1ad', fontWeight: 400 }}>{msg.elapsedMs}ms</span>
          ) : null}
          {msg.streaming ? (
            <span style={{ color: '#9aa1ad', fontWeight: 400 }}>执行中…</span>
          ) : null}
        </div>
        {msg.toolArgs ? (
          <div className="tool-body" style={{ color: '#94a3b8' }}>参数:{msg.toolArgs}</div>
        ) : null}
        {msg.content ? <div className="tool-body">{msg.content}</div> : null}
      </div>
    )
  }

  if (msg.role === 'notice') {
    return <div className={`notice${msg.error ? ' error' : ''}`}>{msg.content}</div>
  }

  return (
    <div className={`msg ${msg.role}`}>
      <div className="avatar">{msg.role === 'user' ? '我' : 'AI'}</div>
      <div className="bubble">
        {msg.role === 'assistant' ? (
          <span dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.content || '') }} />
        ) : (
          <span>{msg.content}</span>
        )}
        {msg.streaming ? <span className="cursor" /> : null}
      </div>
    </div>
  )
}
