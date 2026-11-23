/** 与后端约定的类型与调用封装 */

export interface MemorySnapshot {
  tokensUsed: number
  budgetTokens: number
  summaryTokens: number
  historyMessages: number
  summaryCoveredSeq: number
  compactRuns: number
  archivedMessages: number
  tokensSaved: number
  pinnedFacts: string[]
  summaryPreview: string
}

export interface ConversationSummary {
  id: string
  title: string
  updatedAt: number
  messageCount: number
  tokensUsed: number
  budgetTokens: number
  compactRuns: number
  archivedMessages: number
  summaryPreview: string
}

export interface StoredMessage {
  seq: number
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string | null
  toolCallId?: string
  name?: string
  toolCalls?: { id: string; function: { name: string; arguments: string } }[]
  createdAt: number
}

export interface ConversationDetail {
  id: string
  userId: string
  title: string
  createdAt: number
  updatedAt: number
  summary: string
  summaryCoveredSeq: number
  pinnedFacts: string[]
  messages: StoredMessage[]
  stats: {
    totalMessages: number
    compactRuns: number
    archivedMessages: number
    tokensSavedEstimate: number
    summarizerFailures: number
  }
  tokensUsed: number
  summaryTokens: number
  historyTokens: number
  budgetTokens: number
  archivedCount: number
}

export interface AgentInfo {
  provider: string
  realModel: boolean
  model: string
  maxSteps: number
  tools: string[]
  store: string
  memory: {
    budgetTokens: number
    compactThreshold: number
    keepTailTokens: number
    recallTopK: number
  }
}

export interface MemoryStats {
  store: string
  conversations: number
  activeLocks: number
  summarizerBreakerOpen: boolean
  budgetTokens: number
  ttlSeconds: number
}

export interface RecallHit {
  startSeq: number
  endSeq: number
  text: string
  score: number
}

export interface StreamEvent {
  name: string
  data: Record<string, unknown>
}

const BASE = ''

async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, init)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status} ${text.slice(0, 200)}`)
  }
  return (await res.json()) as T
}

export const api = {
  info: () => getJson<AgentInfo>('/api/agent/info'),
  listConversations: (userId: string) =>
    getJson<ConversationSummary[]>(`/api/conversations?userId=${encodeURIComponent(userId)}`),
  detail: (id: string) => getJson<ConversationDetail>(`/api/conversations/${id}`),
  remove: (id: string) => getJson<unknown>(`/api/conversations/${id}`, { method: 'DELETE' }),
  stats: () => getJson<MemoryStats>('/api/memory/stats'),
  compact: (id: string) =>
    getJson<{ compacted: boolean; mode: string; beforeTokens: number; afterTokens: number }>(
      `/api/conversations/${id}/compact?force=true`,
      { method: 'POST' },
    ),
  archiveSearch: (id: string, q: string) =>
    getJson<{ size: number; hits: RecallHit[] }>(
      `/api/conversations/${id}/archive?q=${encodeURIComponent(q)}`,
    ),
}

/**
 * POST + SSE 的流式对话。
 *
 * 原生 EventSource 只支持 GET,所以这里用 fetch + ReadableStream 自己切帧:
 * 帧与帧之间用空行分隔,每帧里 `event:` 是事件名,`data:` 是 JSON。
 * 注意一个 JSON 里可能包含换行(多行 data),所以必须按空行切,不能按行切。
 */
export async function streamChat(
  body: { message: string; conversationId?: string; userId: string },
  onEvent: (e: StreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(BASE + '/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    throw new Error(`请求失败: ${res.status}`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sep = buffer.indexOf('\n\n')
    while (sep >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const parsed = parseFrame(frame)
      if (parsed) onEvent(parsed)
      sep = buffer.indexOf('\n\n')
    }
  }
}

function parseFrame(frame: string): StreamEvent | null {
  let name = 'message'
  const dataLines: string[] = []
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.trimEnd()
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (!dataLines.length) return null
  const raw = dataLines.join('\n')
  try {
    return { name, data: JSON.parse(raw) as Record<string, unknown> }
  } catch {
    return { name, data: { text: raw } }
  }
}
