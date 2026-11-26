export interface AgentEvent {
  type: string
  t: number
  data: Record<string, any>
}

export type LaneStatus = 'running' | 'ok' | 'degraded' | 'failed' | 'skipped'

export interface Lane {
  id: string
  tool: string
  dependsOn: string[]
  start: number
  end: number | null
  status: LaneStatus
  attempts: number
  retries: number
  repairs: string[]
  error?: string
}

export interface BenchRow {
  policy: string
  success: number
  degraded: number
  failed: number
  successRate: number
  degradedRate: number
  avgMs: number
  p95Ms: number
  calls: number
  retries: number
  repairs: number
  fallbacks: number
  circuitRejects: number
  failureKinds: Record<string, number>
}

export interface BenchReport {
  seed: number
  tasks: number
  latencyScale: number
  totalMs: number
  rows: BenchRow[]
}

export interface ToolSpec {
  name: string
  description: string
  write: boolean
  idempotent: boolean
}

export interface RunResult {
  status: string
  answer: string
  wallMs: number
  calls: number
  retries: number
  repairs: number
  fallbacks: number
}
