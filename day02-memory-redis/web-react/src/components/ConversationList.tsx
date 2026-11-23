import type { ConversationSummary } from '../api/agent'

function pct(c: ConversationSummary): number {
  return c.budgetTokens ? Math.min(100, Math.round((c.tokensUsed / c.budgetTokens) * 100)) : 0
}

function timeText(ts: number): string {
  if (!ts) return ''
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Date(ts).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

export function ConversationList({
  conversations,
  currentId,
  onSelect,
  onRemove,
}: {
  conversations: ConversationSummary[]
  currentId: string | null
  onSelect: (id: string) => void
  onRemove: (id: string) => void
}) {
  return (
    <div className="conv-list">
      {conversations.map((c) => (
        <div
          key={c.id}
          className={`conv-item${c.id === currentId ? ' active' : ''}`}
          onClick={() => onSelect(c.id)}
        >
          <div className="conv-title">{c.title}</div>
          <div className="conv-meta">
            <span>{timeText(c.updatedAt)}</span>
            <span className={`pill${pct(c) > 70 ? ' warn' : ''}`}>{pct(c)}%</span>
            {c.compactRuns > 0 ? <span className="pill">压缩 {c.compactRuns}</span> : null}
          </div>
          <button
            className="del-btn"
            title="删除会话"
            onClick={(e) => {
              e.stopPropagation()
              onRemove(c.id)
            }}
          >
            ×
          </button>
        </div>
      ))}
      {!conversations.length ? (
        <div style={{ padding: 10, fontSize: 12, color: '#9aa1ad' }}>
          还没有历史会话。发一条消息就会自动落库。
        </div>
      ) : null}
    </div>
  )
}
