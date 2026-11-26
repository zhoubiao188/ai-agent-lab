import type { AgentEvent, BenchReport, ToolSpec } from './types'

/**
 * SSE 不能用 EventSource:它只支持 GET,而我们要把任务体 POST 过去。
 * 所以用 fetch + ReadableStream 自己解析 `event:` / `data:` 两个字段。
 * 这段解析逻辑要处理"一个 chunk 里有多条事件"和"一条事件被切成两个 chunk"两种情况,
 * 所以必须维护跨 chunk 的缓冲区。
 */
export async function streamRun(
  body: { task: string; policy: string; faults: boolean; seed: number },
  onEvent: (e: AgentEvent) => void
): Promise<void> {
  const res = await fetch('/api/agent/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body)
  })
  if (!res.ok || !res.body) {
    throw new Error(`请求失败: ${res.status}`)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx = buffer.indexOf('\n\n')
    while (idx >= 0) {
      const chunk = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const event = parseChunk(chunk)
      if (event) onEvent(event)
      idx = buffer.indexOf('\n\n')
    }
  }
}

function parseChunk(chunk: string): AgentEvent | null {
  let name = 'message'
  const dataLines: string[] = []
  for (const line of chunk.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (dataLines.length === 0) return null
  try {
    const parsed = JSON.parse(dataLines.join(''))
    return { type: name, t: parsed.t ?? 0, data: parsed }
  } catch {
    return null
  }
}

export async function fetchTools(): Promise<ToolSpec[]> {
  const res = await fetch('/api/tools')
  return res.json()
}

export async function runBench(tasks: number, seed: number): Promise<BenchReport> {
  const res = await fetch('/api/bench/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tasks, seed, scale: 0.1 })
  })
  if (!res.ok) throw new Error(`评测失败: ${res.status}`)
  return res.json()
}
