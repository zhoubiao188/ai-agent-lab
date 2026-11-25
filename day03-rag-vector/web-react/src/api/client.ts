import type { Citation, EvalReport, KbStats, Mode, RetrieveResponse } from '../types'

const BASE = import.meta.env.VITE_API_BASE ?? ''

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text()
    let msg = `HTTP ${res.status}`
    try {
      msg = JSON.parse(text).error ?? msg
    } catch {
      /* 非 JSON 错误体直接用状态码 */
    }
    throw new Error(msg)
  }
  return res.json() as Promise<T>
}

export function getStats(): Promise<KbStats> {
  return fetch(`${BASE}/api/kb/stats`).then((r) => json<KbStats>(r))
}

export function retrieve(query: string, mode: Mode, topK = 8): Promise<RetrieveResponse> {
  return fetch(`${BASE}/api/retrieve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, mode, topK })
  }).then((r) => json<RetrieveResponse>(r))
}

export function getEvalReport(force = false): Promise<EvalReport> {
  return fetch(`${BASE}/api/eval/report?force=${force}`).then((r) => json<EvalReport>(r))
}

export interface AskHandlers {
  onCitations?: (list: Citation[]) => void
  onDelta?: (text: string) => void
  onDone?: (payload: { answer: string; retrieveMs: number; totalMs: number; model: string }) => void
  onError?: (message: string) => void
}

/**
 * 直接读 fetch 的 ReadableStream 自己解析 SSE。
 * 不用 EventSource 的原因很实际:EventSource 只支持 GET,而问答需要 POST 一个 JSON body。
 */
export async function streamAsk(
  query: string,
  mode: Mode,
  topK: number,
  handlers: AskHandlers,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch(`${BASE}/api/ask`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ query, mode, topK }),
    signal
  })
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '')
    handlers.onError?.(text || `HTTP ${res.status}`)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const handle = (raw: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (dataLines.length === 0) return
    const payload = dataLines.join('\n')
    try {
      const data = JSON.parse(payload)
      if (event === 'citations') handlers.onCitations?.(data as Citation[])
      else if (event === 'delta') handlers.onDelta?.(data.text ?? '')
      else if (event === 'done') handlers.onDone?.(data)
      else if (event === 'error') handlers.onError?.(data.message ?? '未知错误')
    } catch {
      // 单帧解析失败不应该影响后续帧
    }
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx = buffer.indexOf('\n\n')
    while (idx >= 0) {
      handle(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 2)
      idx = buffer.indexOf('\n\n')
    }
  }
  if (buffer.trim()) handle(buffer)
}
