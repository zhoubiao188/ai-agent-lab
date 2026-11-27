export interface SpanEvent {
  name: string
  atMs: number
  attrs: Record<string, unknown>
}

export interface SpanDto {
  id: string
  parentId?: string | null
  name: string
  kind: string
  kindLabel: string
  decisionCode?: string | null
  startMs: number
  durationMs: number
  selfMs: number
  status: string
  errorKind?: string | null
  errorMessage?: string | null
  attempts: number
  tokensIn: number
  tokensOut: number
  costMicros: number
  integrity: boolean
  redactions: number
  attrs: Record<string, unknown>
  events: SpanEvent[]
}

export interface AlternativeDto {
  name: string
  score: number
  reason: string
}

export interface DecisionDto {
  seq: number
  code: string
  title: string
  question: string
  symptom: string
  spanId: string
  chosen: string
  basis: string
  basisLabel: string
  confidence: number
  margin: number
  latencyMs: number
  alternatives: AlternativeDto[]
  attrs: Record<string, unknown>
}

export interface SuspectDto {
  spanId: string
  name: string
  kind: string
  kindLabel: string
  decisionCode?: string | null
  score: number
  contributions: Record<string, number>
  evidence: string[]
}

export interface TraceDetail {
  traceId: string
  taskId: string
  scenario: string
  scenarioLabel: string
  question: string
  userId: string
  startedAt: number
  outcome: string
  outcomeLabel: string
  trust: number
  answer: string
  durationMs: number
  tokens: number
  costMicros: number
  spanCount: number
  decisionCount: number
  retryAmplification: number
  sampled: boolean
  narrative: string
  spans: SpanDto[]
  decisions: DecisionDto[]
  suspects: SuspectDto[]
}

export interface RunSummary {
  traceId: string
  taskId: string
  scenario: string
  scenarioLabel: string
  outcome: string
  outcomeLabel: string
  startedAt: number
  durationMs: number
  trust: number
  spanCount: number
  tokens: number
  costMicros: number
  retryAmplification: number
  silent: boolean
  topSuspect: string
  topSuspectKind: string
}

export interface ToolStat {
  tool: string
  calls: number
  errors: number
  avgAttempts: number
}

export interface Overview {
  runs: number
  successRate: number
  silentRate: number
  p50Ms: number
  p95Ms: number
  avgTokens: number
  costPerSuccessMicros: number
  avgRetryAmp: number
  avgSpans: number
  avgDecisions: number
  tools: ToolStat[]
}

export interface DecisionPointMeta {
  code: string
  title: string
  question: string
  symptom: string
}

export interface ScenarioMeta {
  name: string
  label: string
  description: string
  culpritTool: string
}

export interface TaskMeta {
  id: string
  question: string
  scenario: string
  why: string
}

export interface Catalog {
  decisions: DecisionPointMeta[]
  scenarios: ScenarioMeta[]
  tasks: TaskMeta[]
  toolCount: number
}

export interface FamilyRow {
  scenario: string
  label: string
  incidents: number
  recall1: number
  recall3: number
  mrr: number
  avgRank: number
  avgSpans: number
  avgDecisions: number
  avgTopScore: number
  noCulprit: number
}

export interface BenchBaseline {
  name: string
  description: string
  recall1: number
  recall3: number
  mrr: number
}

export interface BenchOverall {
  recall1: number
  recall3: number
  mrr: number
  avgRank: number
  avgSpans: number
  avgDecisions: number
  avgMs: number
  silentFailures: number
  avgRetryAmp: number
}

export interface BenchReport {
  incidents: number
  seed: number
  generatedAt: string
  families: FamilyRow[]
  overall: BenchOverall
  baselines: BenchBaseline[]
  note: string
}
