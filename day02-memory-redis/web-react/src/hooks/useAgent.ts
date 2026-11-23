import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  api,
  streamChat,
  type AgentInfo,
  type ConversationDetail,
  type ConversationSummary,
  type MemorySnapshot,
  type MemoryStats,
  type RecallHit,
  type StreamEvent,
} from '../api/agent'
import { uid, type UiMessage } from '../types'

const USER_ID = 'demo-user'

function snapshotFromDetail(d: ConversationDetail): MemorySnapshot {
  return {
    tokensUsed: d.tokensUsed,
    budgetTokens: d.budgetTokens,
    summaryTokens: d.summaryTokens,
    historyMessages: d.messages.length,
    summaryCoveredSeq: d.summaryCoveredSeq,
    compactRuns: d.stats.compactRuns,
    archivedMessages: d.stats.archivedMessages,
    tokensSaved: d.stats.tokensSavedEstimate,
    pinnedFacts: d.pinnedFacts ?? [],
    summaryPreview: d.summary ?? '',
  }
}

function toUiMessages(d: ConversationDetail): UiMessage[] {
  const out: UiMessage[] = []
  for (const m of d.messages) {
    if (m.role === 'user') {
      out.push({ key: uid('u'), role: 'user', content: m.content ?? '' })
    } else if (m.role === 'tool') {
      out.push({
        key: uid('t'),
        role: 'tool',
        content: m.content ?? '',
        toolName: m.name,
        success: !(m.content ?? '').startsWith('ERROR:'),
      })
    } else if (m.role === 'assistant' && m.content?.trim()) {
      out.push({ key: uid('a'), role: 'assistant', content: m.content })
    }
  }
  return out
}

/**
 * 把"会话 + 记忆"的状态机收在一个 hook 里,组件只负责渲染。
 * 这样 Vue / React 两端可以共享同一套后端契约,差异只留在视图层。
 */
export function useAgent() {
  const [info, setInfo] = useState<AgentInfo | null>(null)
  const [stats, setStats] = useState<MemoryStats | null>(null)
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [currentId, setCurrentId] = useState<string | null>(null)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [memory, setMemory] = useState<MemorySnapshot | null>(null)
  const [recallHits, setRecallHits] = useState<RecallHit[]>([])
  const [archivedCount, setArchivedCount] = useState(0)
  const [sending, setSending] = useState(false)
  const [compacting, setCompacting] = useState(false)
  const [toast, setToast] = useState('')
  const toastTimer = useRef<number | undefined>(undefined)

  const showToast = useCallback((text: string) => {
    setToast(text)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(''), 4200)
  }, [])

  const refreshList = useCallback(async () => {
    try {
      setConversations(await api.listConversations(USER_ID))
    } catch (e) {
      console.warn('拉取会话列表失败', e)
    }
  }, [])

  const refreshStats = useCallback(async () => {
    try {
      setStats(await api.stats())
    } catch {
      /* 忽略 */
    }
  }, [])

  const refreshMemory = useCallback(async (id?: string | null) => {
    const target = id ?? currentId
    if (!target) return
    try {
      const d = await api.detail(target)
      setMemory(snapshotFromDetail(d))
      setArchivedCount(d.archivedCount)
    } catch {
      /* 忽略 */
    }
  }, [currentId])

  const openConversation = useCallback(async (id: string) => {
    setCurrentId(id)
    setRecallHits([])
    try {
      const d = await api.detail(id)
      setMessages(toUiMessages(d))
      setMemory(snapshotFromDetail(d))
      setArchivedCount(d.archivedCount)
    } catch (e) {
      showToast(`打开会话失败:${(e as Error).message}`)
    }
  }, [showToast])

  const newConversation = useCallback(() => {
    setCurrentId(null)
    setMessages([])
    setMemory(null)
    setRecallHits([])
    setArchivedCount(0)
  }, [])

  const removeConversation = useCallback(async (id: string) => {
    try {
      await api.remove(id)
      if (currentId === id) newConversation()
      await refreshList()
    } catch (e) {
      showToast(`删除失败:${(e as Error).message}`)
    }
  }, [currentId, newConversation, refreshList, showToast])

  const compact = useCallback(async () => {
    if (!currentId || compacting) return
    setCompacting(true)
    try {
      const r = await api.compact(currentId)
      showToast(r.compacted
        ? `已压缩:${r.beforeTokens} → ${r.afterTokens} token(${r.mode})`
        : `本次没有压缩(${r.mode})`)
      await openConversation(currentId)
      await refreshList()
    } catch (e) {
      showToast(`压缩失败:${(e as Error).message}`)
    } finally {
      setCompacting(false)
    }
  }, [currentId, compacting, openConversation, refreshList, showToast])

  const send = useCallback(async (rawText: string) => {
    const text = rawText.trim()
    if (!text || sending) return

    setSending(true)
    setRecallHits([])
    setMessages((prev) => [...prev, { key: uid('u'), role: 'user', content: text }])

    const conversationId = currentId ?? crypto.randomUUID()
    setCurrentId(conversationId)

    // 流式过程中需要"就地更新最后一个气泡",用 ref 记录当前气泡的 key
    let assistantKey: string | null = null
    let lastToolKey: string | null = null

    const appendDelta = (delta: string) => {
      setMessages((prev) => {
        if (!assistantKey) {
          assistantKey = uid('a')
          return [...prev, { key: assistantKey, role: 'assistant', content: delta, streaming: true }]
        }
        return prev.map((m) => (m.key === assistantKey ? { ...m, content: m.content + delta } : m))
      })
    }

    const handle = (e: StreamEvent) => {
      switch (e.name) {
        case 'message_start':
          if (e.data.memory) setMemory(e.data.memory as MemorySnapshot)
          break
        case 'delta':
          appendDelta(String(e.data.text ?? ''))
          break
        case 'delta_reset':
          if (assistantKey) {
            const key = assistantKey
            setMessages((prev) => prev.map((m) => (m.key === key ? { ...m, content: '' } : m)))
          }
          break
        case 'tool_start': {
          // 过渡语气泡如果还是空的,直接摘掉,让答案另起一个气泡排在卡片后面
          if (assistantKey) {
            const key = assistantKey
            assistantKey = null
            setMessages((prev) => prev.filter((m) => !(m.key === key && !m.content.trim())))
          }
          lastToolKey = uid('t')
          const key = lastToolKey
          setMessages((prev) => [...prev, {
            key,
            role: 'tool',
            toolName: String(e.data.name ?? 'tool'),
            content: '',
            streaming: true,
          }])
          break
        }
        case 'tool_result': {
          const key = lastToolKey
          if (!key) break
          setMessages((prev) => prev.map((m) => (m.key === key ? {
            ...m,
            content: String(e.data.content ?? ''),
            toolArgs: String(e.data.arguments ?? ''),
            success: Boolean(e.data.success),
            elapsedMs: Number(e.data.elapsedMs ?? 0),
            streaming: false,
          } : m)))
          break
        }
        case 'memory_compacted':
          showToast(`记忆压缩:折叠 ${e.data.foldedMessages} 条消息,${e.data.beforeTokens} → ${e.data.afterTokens} token(${e.data.mode})`)
          break
        case 'memory_recall':
          setRecallHits((e.data.hits as RecallHit[]) ?? [])
          break
        case 'message_end': {
          const content = String(e.data.content ?? '')
          setMessages((prev) => {
            if (!assistantKey) {
              const key = uid('a')
              assistantKey = key
              return [...prev, { key, role: 'assistant', content, streaming: false }]
            }
            return prev.map((m) => (m.key === assistantKey ? { ...m, content, streaming: false } : m))
          })
          if (e.data.memory) setMemory(e.data.memory as MemorySnapshot)
          break
        }
        case 'error':
          setMessages((prev) => [...prev, {
            key: uid('e'),
            role: 'notice',
            content: `出错了:${String(e.data.message ?? '未知错误')}${e.data.retryable ? '(可直接重发)' : ''}`,
            error: true,
          }])
          break
        default:
          break
      }
    }

    try {
      await streamChat({ message: text, conversationId, userId: USER_ID }, handle)
    } catch (e) {
      setMessages((prev) => [...prev, {
        key: uid('e'),
        role: 'notice',
        content: `连接中断:${(e as Error).message}`,
        error: true,
      }])
    } finally {
      setSending(false)
      setMessages((prev) => prev.map((m) => ({ ...m, streaming: false })))
      await refreshList()
      await refreshStats()
      await refreshMemory(conversationId)
    }
  }, [currentId, refreshList, refreshMemory, refreshStats, sending, showToast])

  useEffect(() => {
    void (async () => {
      try {
        setInfo(await api.info())
      } catch {
        /* 后端未启动时也能渲染骨架 */
      }
      await refreshList()
      await refreshStats()
    })()
  }, [refreshList, refreshStats])

  const percent = useMemo(() => {
    const used = memory?.tokensUsed ?? 0
    const budget = memory?.budgetTokens ?? info?.memory.budgetTokens ?? 6000
    return budget ? Math.min(100, Math.round((used / budget) * 100)) : 0
  }, [memory, info])

  const currentTitle = useMemo(() => {
    const hit = conversations.find((c) => c.id === currentId)
    return hit?.title || (messages.length ? '未命名会话' : '新对话')
  }, [conversations, currentId, messages.length])

  return {
    info, stats, conversations, currentId, messages, memory, recallHits,
    archivedCount, sending, compacting, toast, percent, currentTitle,
    send, openConversation, newConversation, removeConversation, compact, refreshMemory,
  }
}
