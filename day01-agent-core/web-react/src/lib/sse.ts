export interface AgentEvent {
  event: string
  data: any
}

export interface StreamOptions {
  message: string
  conversationId?: string
  userId?: string
  signal?: AbortSignal
  onEvent: (e: AgentEvent) => void
}

export const CLIENT_ID = getOrCreateClientId()

/**
 * SSE over POST。与 Vue 版共用同一套协议,只是这边的消费方式是回调。
 */
export async function streamChat(opts: StreamOptions): Promise<void> {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Client-Id': CLIENT_ID,
    },
    body: JSON.stringify({
      message: opts.message,
      conversationId: opts.conversationId,
      userId: opts.userId ?? CLIENT_ID,
    }),
    signal: opts.signal,
  })

  if (!resp.ok || !resp.body) {
    const text = await resp.text().catch(() => '')
    throw new Error(`HTTP ${resp.status} ${text.slice(0, 200)}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const evt = parseFrame(raw)
      if (evt) opts.onEvent(evt)
    }
  }
  if (buffer.trim()) {
    const evt = parseFrame(buffer)
    if (evt) opts.onEvent(evt)
  }
}

function parseFrame(raw: string): AgentEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  const payload = data.join('\n')
  try {
    return { event, data: JSON.parse(payload) }
  } catch {
    return { event, data: payload }
  }
}

function getOrCreateClientId(): string {
  const key = 'agent_client_id'
  let id = localStorage.getItem(key)
  if (!id) {
    id = 'react-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem(key, id)
  }
  return id
}
