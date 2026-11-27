export interface TaskItem {
  id: string
  request: string
  slots: string[]
  orderId: string
  flaw: string
}

export interface PolicyItem {
  name: string
  validate: boolean
  coverage: boolean
  patch: boolean
  critic: boolean
  replan: boolean
}

export interface PlanStep {
  id: string
  tool: string
  title: string
  dependsOn: string[]
  covers: string[]
  produces: string[]
  args: string
  write: boolean
}

export type StepStatus = 'idle' | 'running' | 'ok' | 'fail' | 'skip' | 'cached'

export interface AgentEvent {
  type: string
  t: number
  data: Record<string, any>
}

export interface BenchRow {
  policy: string
  total: number
  success: number
  falseSuccess: number
  blocked: number
  successRate: number
  avgSteps: number
  avgMs: number
  p95Ms: number
  replans: number
  failureKinds: Record<string, number>
}

export interface TaskDetail {
  taskId: string
  request: string
  flaw: string
  byPolicy: Record<string, string>
}

export interface BenchReport {
  seed: number
  taskCount: number
  rows: BenchRow[]
  details: TaskDetail[]
}

export const SLOT_LABEL: Record<string, string> = {
  order: '查订单',
  logistics: '查物流',
  inventory: '查库存',
  quote: '算可退金额',
  refund: '提交退款',
  ticket: '建工单'
}
