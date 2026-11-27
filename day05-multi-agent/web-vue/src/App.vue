<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchPolicies, fetchTasks, runBench, streamRun } from './api'
import type { AgentEvent, BenchReport, PlanStep, PolicyItem, StepStatus, TaskItem } from './types'
import PlanTree from './components/PlanTree.vue'
import BenchTable from './components/BenchTable.vue'

const tasks = ref<TaskItem[]>([])
const policies = ref<PolicyItem[]>([])
const current = ref('T03')
const policy = ref('C3-复核重规划')
const flaw = ref('AUTO')

const running = ref(false)
const steps = ref<PlanStep[]>([])
const status = ref<Record<string, StepStatus>>({})
const durations = ref<Record<string, number>>({})
const errors = ref<Record<string, string>>({})
const violations = ref<string[]>([])
const missing = ref<string[]>([])
const covered = ref<string[]>([])
const verdict = ref('')
const flawLabel = ref('')
const answer = ref<any>(null)
const logs = ref<{ t: number; type: string; text: string; tone: string }[]>([])

const benchReport = ref<BenchReport | null>(null)
const benchLoading = ref(false)

const currentTask = computed(() => tasks.value.find((t) => t.id === current.value))

onMounted(async () => {
  tasks.value = await fetchTasks()
  policies.value = await fetchPolicies()
})

function push(e: AgentEvent, text: string, tone = '') {
  logs.value.push({ t: e.t, type: e.type, text, tone })
}

function resetRun() {
  steps.value = []
  status.value = {}
  durations.value = {}
  errors.value = {}
  violations.value = []
  missing.value = []
  covered.value = []
  verdict.value = ''
  flawLabel.value = ''
  answer.value = null
  logs.value = []
}

function apply(e: AgentEvent) {
  const d = e.data
  switch (e.type) {
    case 'task':
      push(e, `${d.taskId} · ${d.policy}`)
      break
    case 'plan':
      steps.value = d.steps
      push(e, `生成计划:${d.count} 步`)
      break
    case 'plan_flaw':
      flawLabel.value = d.label
      push(e, `计划缺陷:${d.label} —— ${d.symptom}`, 'bad')
      break
    case 'validate_ok':
      push(e, `静态校验通过(${d.steps} 步)`, 'ok')
      break
    case 'validate_fail':
      violations.value = d.violations
      push(e, `静态校验不通过:${d.violations.length} 条`, 'bad')
      break
    case 'coverage':
      covered.value = d.covered
      missing.value = d.missing
      push(e, `覆盖核对:${d.covered.length}/${d.required.length}`, d.missing.length ? 'warn' : 'ok')
      break
    case 'coverage_patch':
      push(e, `机械补步:补上 ${d.added.join(',')}`, 'warn')
      break
    case 'repair_patch':
      push(e, `复核建议补步,已补到 ${d.steps} 步`, 'warn')
      break
    case 'step_start':
      status.value = { ...status.value, [d.stepId]: 'running' }
      push(e, `▶ ${d.stepId} ${d.tool}`)
      break
    case 'step_ok':
      status.value = { ...status.value, [d.stepId]: 'ok' }
      durations.value = { ...durations.value, [d.stepId]: d.ms }
      push(e, `✓ ${d.stepId} ${d.tool} ${d.ms}ms`, 'ok')
      break
    case 'step_fail':
      status.value = { ...status.value, [d.stepId]: 'fail' }
      errors.value = { ...errors.value, [d.stepId]: d.error }
      push(e, `✗ ${d.stepId} ${d.tool}: ${d.error}`, 'bad')
      break
    case 'step_skip':
      status.value = { ...status.value, [d.stepId]: 'skip' }
      push(e, `↷ ${d.stepId} 跳过:${d.reason}`, 'warn')
      break
    case 'step_cached':
      status.value = { ...status.value, [d.stepId]: 'cached' }
      push(e, `↺ ${d.stepId} 复用上次结果`, 'warn')
      break
    case 'critic':
      verdict.value = d.verdict
      push(e, `复核结论 ${d.verdict}${d.findings.length ? ':' + d.findings.join(';') : ''}`,
        d.verdict === 'PASS' ? 'ok' : 'warn')
      break
    case 'replan':
      push(e, `重规划 #${d.round}:${d.reason}`, 'warn')
      break
    case 'replan_abort':
      push(e, `放弃重规划:${d.reason}`, 'warn')
      break
    case 'answer':
      answer.value = d
      push(e, `${d.success ? '交付' : '未完成'}:${d.text}`, d.success ? 'ok' : 'bad')
      break
    case 'done':
      push(e, `结束 ${d.durationMs}ms,${d.steps} 步,重规划 ${d.replans} 次`, d.success ? 'ok' : 'bad')
      break
    case 'error':
      push(e, `服务端错误:${d.message}`, 'bad')
      break
  }
}

async function run() {
  if (running.value) return
  resetRun()
  running.value = true
  try {
    await streamRun({ taskId: current.value, policy: policy.value, flaw: flaw.value }, apply)
  } catch (err: any) {
    logs.value.push({ t: 0, type: 'error', text: String(err.message ?? err), tone: 'bad' })
  } finally {
    running.value = false
  }
}

async function bench(limit = 0) {
  if (benchLoading.value) return
  benchLoading.value = true
  try {
    benchReport.value = await runBench(limit)
  } finally {
    benchLoading.value = false
  }
}

function toneClass(tone: string) {
  return tone
}
</script>

<template>
  <div class="app">
    <header class="top">
      <h1>多 Agent 协作 · 计划错了怎么发现</h1>
      <span class="sub">Planner / Executor / Critic + 四组防线对照</span>
    </header>

    <div class="layout">
      <div>
        <div class="panel">
          <h2>任务</h2>
          <div class="task-list">
            <div v-for="t in tasks" :key="t.id" class="task-item"
                 :class="{ active: t.id === current }" @click="current = t.id">
              <span class="tid">{{ t.id }}</span>{{ t.request }}
              <span v-if="t.flaw !== 'CLEAN'" class="flaw">·{{ t.flaw }}</span>
            </div>
          </div>
        </div>

        <div class="split" />

        <div class="panel">
          <h2>运行</h2>
          <label class="field">
            <span>防线配置</span>
            <select v-model="policy">
              <option v-for="p in policies" :key="p.name" :value="p.name">{{ p.name }}</option>
            </select>
          </label>
          <label class="field">
            <span>计划缺陷</span>
            <select v-model="flaw">
              <option value="AUTO">用任务自带的</option>
              <option value="NONE">不注入(干净计划)</option>
              <option value="UNDER_COVER">拆漏</option>
              <option value="WRONG_ORDER">拆错(金额写死)</option>
              <option value="OVER_SPLIT">拆多(重复调用)</option>
            </select>
          </label>
          <button class="primary" :disabled="running" @click="run">
            {{ running ? '运行中…' : '运行这个任务' }}
          </button>
          <div class="hint">{{ currentTask?.request }}</div>
        </div>
      </div>

      <div>
        <div class="panel">
          <h2>计划与执行</h2>
          <div class="badges">
            <span class="badge" v-if="policy">策略 {{ policy }}</span>
            <span class="badge bad" v-if="flawLabel">缺陷 {{ flawLabel }}</span>
            <span class="badge ok" v-if="verdict">复核 {{ verdict }}</span>
            <span class="badge ok" v-if="answer?.success">金标准通过</span>
            <span class="badge bad" v-if="answer && !answer.success">金标准不通过</span>
            <span class="badge warn" v-if="answer?.claimed && !answer?.success">假成功</span>
          </div>

          <PlanTree :steps="steps" :status="status" :durations="durations" :errors="errors"
                    :violations="violations" :missing="missing" :covered="covered" />

          <div v-if="answer" class="answer" :class="answer.success ? 'ok' : 'bad'">
            <div><span class="k">{{ answer.success ? '交付' : '未完成' }}</span>:{{ answer.text }}</div>
            <div v-if="!answer.success" class="hint">原因:{{ (answer.reasons || []).join(';') }}</div>
          </div>
        </div>

        <div class="split" />

        <div class="panel">
          <h2>事件流</h2>
          <div class="log">
            <div v-for="(l, i) in logs" :key="i" class="line" :class="toneClass(l.tone)">
              <span class="t">{{ l.t }}ms</span>{{ l.text }}
            </div>
            <div v-if="!logs.length" class="hint">选一个任务,点「运行这个任务」。</div>
          </div>
        </div>
      </div>
    </div>

    <div class="split" />

    <div class="panel">
      <h2>四组对照</h2>
      <div class="badges">
        <button class="ghost" style="width: auto" :disabled="benchLoading" @click="bench(0)">
          {{ benchLoading ? '跑着…' : '跑四组对照(20 个任务)' }}
        </button>
        <button class="ghost" style="width: auto" :disabled="benchLoading" @click="bench(6)">只跑前 6 个</button>
        <span class="hint" v-if="benchReport">seed {{ benchReport.seed }},{{ benchReport.taskCount }} 个任务</span>
      </div>
      <BenchTable :report="benchReport" />
    </div>
  </div>
</template>
