/**
 * SSE 客户端。
 *
 * 为什么不用 EventSource:
 *   1. EventSource 只支持 GET,而对话需要 POST 一个 JSON body(多轮 history、参数都塞不进 query);
 *   2. EventSource 无法自定义 header(带不上 token / X-Client-Id);
 *   3. EventSource 断线自动重连对"生成类"请求是灾难 —— 会重复触发一次完整推理。
 *
 * 因此用 fetch + ReadableStream 自己解析 SSE 帧。协议本身很简单:
 *   event: <name>\n data: <json>\n\n 表示一个事件,帧之间用空行分隔。
 */

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

const CLIENT_ID = getOrCreateClientId()

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

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    // stream: true 很关键,否则一个多字节汉字被切在两个 chunk 里会变乱码
    buffer += decoder.decode(value, { stream: true })

    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const evt = parseFrame(raw)
      if (evt) opts.onEvent(evt)
    }
  }
  // 处理结尾可能没有空行的最后一帧
  if (buffer.trim()) {
    const evt = parseFrame(buffer)
    if (evt) opts.onEvent(evt)
  }
}

function parseFrame(raw: string): AgentEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (dataLines.length === 0) return null
  const payload = dataLines.join('\n')
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
    id = 'web-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem(key, id)
  }
  return id
}
