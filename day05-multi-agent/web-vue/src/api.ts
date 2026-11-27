import type { AgentEvent, BenchReport, PolicyItem, TaskItem } from './types'

/**
 * SSE 不能用 EventSource:它只支持 GET,而这里要把任务体 POST 过去。
 * 只好用 fetch + ReadableStream 自己解析 `event:` / `data:`。
 * 这段代码必须处理两种情况:一个 chunk 里有好几条事件、一条事件被切成两个 chunk,
 * 所以缓冲区要跨 chunk 保留。
 */
export async function streamRun(
  body: { taskId: string; policy: string; flaw: string },
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

export async function fetchTasks(): Promise<TaskItem[]> {
  const res = await fetch('/api/tasks')
  return res.json()
}

export async function fetchPolicies(): Promise<PolicyItem[]> {
  const res = await fetch('/api/policies')
  return res.json()
}

export async function runBench(limit = 0): Promise<BenchReport> {
  const res = await fetch(`/api/bench${limit > 0 ? `?limit=${limit}` : ''}`)
  if (!res.ok) throw new Error(`对照实验失败: ${res.status}`)
  return res.json()
}
