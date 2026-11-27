import type { BenchReport, Catalog, Overview, RunSummary, TraceDetail } from './types'

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...init
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status} ${res.statusText}: ${text}`)
  }
  return (await res.json()) as T
}

export interface RunResponse {
  traceId: string
  answer: string
  outcome: string
  trust: number
  durationMs: number
  tokens: number
  suspects: TraceDetail['suspects']
  narrative: string
}

export const api = {
  catalog: () => json<Catalog>('/api/meta/catalog'),
  overview: () => json<Overview>('/api/metrics/overview'),
  runs: (limit = 20) => json<RunSummary[]>(`/api/runs?limit=${limit}`),
  trace: (id: string) => json<TraceDetail>(`/api/runs/${id}`),
  run: (question: string, scenario: string) =>
    json<RunResponse>('/api/runs', {
      method: 'POST',
      body: JSON.stringify({ question, scenario })
    }),
  bench: (incidents = 200) =>
    json<BenchReport>(`/api/bench/triage?incidents=${incidents}`, { method: 'POST' })
}
