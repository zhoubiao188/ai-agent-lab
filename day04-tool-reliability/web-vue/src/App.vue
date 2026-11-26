<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Timeline from './components/Timeline.vue'
import BenchTable from './components/BenchTable.vue'
import { fetchTools, runBench, streamRun } from './api'
import type { AgentEvent, BenchReport, Lane, RunResult, ToolSpec } from './types'

const task = ref('处理订单 A10003 的退款申请,并跟进物流异常')
const policy = ref('full')
const faults = ref(true)
const seed = ref(20261126)
const running = ref(false)
const lanes = ref<Lane[]>([])
const events = ref<{ type: string; t: number }[]>([])
const result = ref<RunResult | null>(null)
const error = ref('')
const tools = ref<ToolSpec[]>([])
const bench = ref<BenchReport | null>(null)
const benchLoading = ref(false)

const total = computed(() => {
  if (result.value) return Math.max(result.value.wallMs, 1)
  const ends = lanes.value.map((l) => l.end ?? l.start)
  return Math.max(1, ...ends)
})

const retries = computed(() => lanes.value.reduce((n, l) => n + l.retries, 0))
const repairs = computed(() => lanes.value.reduce((n, l) => n + l.repairs.length, 0))
const degraded = computed(() => lanes.value.filter((l) => l.status === 'degraded').length)
const failed = computed(() => lanes.value.filter((l) => l.status === 'failed' || l.status === 'skipped').length)

onMounted(async () => {
  try {
    tools.value = await fetchTools()
  } catch {
    error.value = '后端没起来?先执行 mvn -pl tool-service spring-boot:run'
  }
})

function laneOf(id: string): Lane | undefined {
  return lanes.value.find((l) => l.id === id)
}

function upsert(callId: string, tool: string, dependsOn: string[] = []): Lane {
  let lane = laneOf(callId)
  if (!lane) {
    lane = {
      id: callId,
      tool,
      dependsOn,
      start: 0,
      end: null,
      status: 'running',
      attempts: 0,
      retries: 0,
      repairs: []
    }
    lanes.value.push(lane)
  }
  return lane
}

function onEvent(e: AgentEvent) {
  events.value.push({ type: e.type, t: e.t })
  if (events.value.length > 400) events.value.shift()

  switch (e.type) {
    case 'planned': {
      for (const c of e.data.calls ?? []) {
        const lane = upsert(c.callId, c.tool, c.dependsOn ?? [])
        lane.start = e.t
        lane.status = 'running'
      }
      break
    }
    case 'tool_start': {
      const lane = upsert(e.data.callId, e.data.tool)
      lane.attempts += 1
      if (lane.status !== 'running') lane.status = 'running'
      break
    }
    case 'retry': {
      const lane = laneOf(e.data.callId)
      if (lane) lane.retries += 1
      break
    }
    case 'repair': {
      const lane = laneOf(e.data.callId)
      if (lane && Array.isArray(e.data.repairs)) lane.repairs.push(...e.data.repairs)
      break
    }
    case 'tool_ok': {
      const lane = laneOf(e.data.callId)
      if (lane) {
        lane.end = e.data.at ?? e.t
        if (lane.status !== 'degraded') lane.status = 'ok'
      }
      break
    }
    case 'tool_fail': {
      const lane = laneOf(e.data.callId)
      if (lane) {
        lane.end = e.t
        lane.status = 'failed'
        lane.error = `${e.data.kind}: ${e.data.error ?? ''}`
      }
      break
    }
    case 'tool_fallback': {
      const lane = laneOf(e.data.callId)
      if (lane) lane.status = 'degraded'
      break
    }
    case 'tool_skip': {
      const lane = laneOf(e.data.callId)
      if (lane) {
        lane.end = e.t
        lane.status = 'skipped'
        lane.error = String(e.data.reason ?? '')
      }
      break
    }
    case 'result': {
      result.value = e.data as unknown as RunResult
      break
    }
    case 'error': {
      error.value = String(e.data.message ?? '未知错误')
      break
    }
  }
}

async function run() {
  running.value = true
  error.value = ''
  result.value = null
  lanes.value = []
  events.value = []
  try {
    await streamRun(
      { task: task.value, policy: policy.value, faults: faults.value, seed: seed.value },
      onEvent
    )
  } catch (e) {
    error.value = String(e)
  } finally {
    running.value = false
  }
}

async function benchRun() {
  benchLoading.value = true
  try {
    bench.value = await runBench(120, seed.value)
  } catch (e) {
    error.value = String(e)
  } finally {
    benchLoading.value = false
  }
}
</script>

<template>
  <div class="app">
    <header class="top">
      <h1>Agent 工具调用可靠性控制台</h1>
      <span class="sub">Spring Boot 3 · Spring Cloud Gateway · Vue 3 —— 参数修复 / 超时重试 / 依赖并行 / 熔断降级</span>
    </header>

    <div class="grid">
      <div>
        <section class="card">
          <h2>任务</h2>
          <label class="field" for="task">一句话任务(带订单号)</label>
          <textarea id="task" v-model="task" class="task" />
          <div class="row">
            <select v-model="policy">
              <option value="l0">L0 · 裸调用</option>
              <option value="l1">L1 · 加重试</option>
              <option value="l2">L2 · 加重试+修复</option>
              <option value="full">L3 · 全量(并行+熔断+降级)</option>
            </select>
            <button class="primary" :disabled="running" @click="run">
              {{ running ? '运行中…' : '运行' }}
            </button>
          </div>
          <div class="row switches">
            <label><input v-model="faults" type="checkbox" /> 注入故障(超时/429/5xx/业务拒绝/参数污染)</label>
            <label>seed <input v-model.number="seed" class="num" type="number" style="width: 108px" /></label>
          </div>
          <p class="hint">同一个 seed + 同一个任务,故障序列完全一致,方便对着时间线复盘。</p>
        </section>

        <section class="card">
          <h2>工具清单</h2>
          <div class="tools">
            <span v-for="t in tools" :key="t.name" class="tool-chip">
              {{ t.name }}
              <span :class="t.write ? 'w' : 'r'">{{ t.write ? 'W' : 'R' }}</span>
              <span v-if="t.write" :class="t.idempotent ? 'r' : 'w'">
                {{ t.idempotent ? '·幂等' : '·非幂等' }}
              </span>
            </span>
          </div>
          <p class="hint">W 写操作 · R 读操作。非幂等写操作的重试开关永远是关的。</p>
        </section>

        <section class="card">
          <h2>本次运行</h2>
          <div class="stats">
            <div class="stat">
              <div class="k">状态</div>
              <div class="v" :class="result?.status === 'SUCCESS' ? 'ok' : result ? 'warn' : ''">
                {{ result?.status ?? (running ? '…' : '—') }}
              </div>
            </div>
            <div class="stat">
              <div class="k">总耗时</div>
              <div class="v">{{ result ? result.wallMs + 'ms' : '—' }}</div>
            </div>
            <div class="stat">
              <div class="k">调用</div>
              <div class="v">{{ result?.calls ?? lanes.length }}</div>
            </div>
            <div class="stat">
              <div class="k">重试</div>
              <div class="v warn">{{ result?.retries ?? retries }}</div>
            </div>
            <div class="stat">
              <div class="k">参数修复</div>
              <div class="v">{{ result?.repairs ?? repairs }}</div>
            </div>
            <div class="stat">
              <div class="k">降级</div>
              <div class="v warn">{{ result?.fallbacks ?? degraded }}</div>
            </div>
            <div class="stat">
              <div class="k">失败/跳过</div>
              <div class="v bad">{{ failed }}</div>
            </div>
          </div>
          <div class="answer" v-if="result?.answer">{{ result.answer }}</div>
          <p v-if="error" class="hint" style="color: #9b2c2c">{{ error }}</p>
        </section>
      </div>

      <div>
        <section class="card">
          <h2>调用时间线(按依赖就绪并发)</h2>
          <Timeline :lanes="lanes" :total="total" />
          <div class="badges">
            <span class="badge ok">成功</span>
            <span class="badge retry">重试</span>
            <span class="badge repair">参数修复</span>
            <span class="badge fail">失败</span>
            <span class="badge">跳过</span>
          </div>
          <pre class="log" v-if="events.length">{{ events.map((e) => `${String(e.t).padStart(5, ' ')}ms  ${e.type}`).join('\n') }}</pre>
        </section>

        <section class="card">
          <h2>对照实验:L0 → L3</h2>
          <div class="row" style="margin-top: 0; margin-bottom: 10px">
            <button class="ghost" :disabled="benchLoading" @click="benchRun">
              {{ benchLoading ? '评测中…' : '跑一次对照实验' }}
            </button>
          </div>
          <BenchTable :report="bench" :loading="benchLoading" />
        </section>
      </div>
    </div>
  </div>
</template>
