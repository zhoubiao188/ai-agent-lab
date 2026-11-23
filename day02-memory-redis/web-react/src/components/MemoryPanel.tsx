import type { MemorySnapshot, RecallHit } from '../api/agent'

export function MemoryPanel({
  memory,
  recallHits,
  archivedCount,
  compacting,
  percent,
  onCompact,
  onRefresh,
}: {
  memory: MemorySnapshot | null
  recallHits: RecallHit[]
  archivedCount: number
  compacting: boolean
  percent: number
  onCompact: () => void
  onRefresh: () => void
}) {
  const level = percent > 85 ? 'danger' : percent > 60 ? 'warn' : ''
  const summary = memory?.summaryPreview ?? ''
  const facts = memory?.pinnedFacts ?? []

  return (
    <div className="memory-body">
      <div>
        <div className="section-title">
          <span>上下文水位</span>
          <span>{percent}%</span>
        </div>
        <div className="meter-bar" style={{ margin: '6px 0 8px' }}>
          <div className={`meter-fill ${level}`} style={{ width: `${percent}%` }} />
        </div>
        <div className="metric-grid">
          <div className="metric">
            <div className="k">当前占用</div>
            <div className="v">
              {memory?.tokensUsed ?? 0}
              <small>/ {memory?.budgetTokens ?? 6000} token</small>
            </div>
          </div>
          <div className="metric">
            <div className="k">摘要占用</div>
            <div className="v">
              {memory?.summaryTokens ?? 0}
              <small>token</small>
            </div>
          </div>
          <div className="metric">
            <div className="k">压缩次数</div>
            <div className="v">{memory?.compactRuns ?? 0}</div>
          </div>
          <div className="metric">
            <div className="k">估算省下</div>
            <div className="v">
              {memory?.tokensSaved ?? 0}
              <small>token</small>
            </div>
          </div>
        </div>
      </div>

      <div>
        <div className="section-title">
          <span>长期记忆摘要</span>
          <button className="link-btn" disabled={compacting} onClick={onCompact}>
            {compacting ? '压缩中…' : '立即压缩'}
          </button>
        </div>
        <div className={`summary-box${summary ? '' : ' empty'}`}>
          {summary || '还没有触发压缩。当上下文超过预算的 72%,最早的整轮对话会被折叠成摘要。'}
        </div>
      </div>

      <div>
        <div className="section-title">
          <span>关键事实(原文保真)</span>
          <span>{facts.length}</span>
        </div>
        {facts.length ? (
          <div className="chips">
            {facts.map((f) => (
              <span key={f} className="chip">{f}</span>
            ))}
          </div>
        ) : (
          <div className="memory-empty">
            单号、金额、日期这类信息在压缩时由正则单独抽取,不交给模型改写。
          </div>
        )}
      </div>

      <div>
        <div className="section-title">
          <span>归档召回</span>
          <span>{archivedCount} 条</span>
        </div>
        {recallHits.length ? (
          recallHits.map((h) => (
            <div key={`${h.startSeq}-${h.endSeq}`} className="recall-item">
              <b>
                seq {h.startSeq}-{h.endSeq} · {h.score.toFixed(2)}
              </b>
              <div>{h.text}</div>
            </div>
          ))
        ) : (
          <div className="memory-empty">
            压缩后的原文留在归档里。当你的问题与某段历史相关时,会被 BM25 打分召回并注入上下文。
          </div>
        )}
      </div>

      <button className="link-btn" onClick={onRefresh}>刷新记忆状态</button>
    </div>
  )
}
