<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from './api'
import BenchPanel from './components/BenchPanel.vue'
import DecisionLedger from './components/DecisionLedger.vue'
import SuspectList from './components/SuspectList.vue'
import Waterfall from './components/Waterfall.vue'
import type { Catalog, Overview, RunSummary, SpanDto, TraceDetail } from './types'

const catalog = ref<Catalog | null>(null)
const runs = ref<RunSummary[]>([])
const trace = ref<TraceDetail | null>(null)
const overview = ref<Overview | null>(null)
const tab = ref<'locate' | 'ledger' | 'bench'>('locate')
const selectedSpanId = ref<string>('')
const loading = ref(false)
const error = ref('')
const runningScenario = ref('')

const selectedSpan = computed<SpanDto | undefined>(() =>
  trace.value?.spans.find((s) => s.id === selectedSpanId.value)
)

function fmtMs(ms: number): string {
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return ms.toFixed(1) + ' ms'
}

function fmtCost(micros: number): string {
  return (micros / 1_000_000).toFixed(4) + ' 元'
}

function outcomeClass(outcome: string): string {
  if (outcome === 'SUCCESS') return 'badge ok'
  if (outcome === 'SILENT_FAILURE') return 'badge warn'
  return 'badge bad'
}

async function refreshList() {
  runs.value = await api.runs(30)
  overview.value = await api.overview()
}

async function openTrace(id: string) {
  loading.value = true
  error.value = ''
  try {
    trace.value = await api.trace(id)
    selectedSpanId.value = trace.value.suspects[0]?.spanId ?? trace.value.spans[0]?.id ?? ''
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function runScenario(scenario: string, question: string) {
  runningScenario.value = scenario
  error.value = ''
  try {
    const res = await api.run(question, scenario)
    await refreshList()
    await openTrace(res.traceId)
    tab.value = 'locate'
  } catch (e) {
    error.value = String(e)
  } finally {
    runningScenario.value = ''
  }
}

function onSelectSpan(span: SpanDto) {
  selectedSpanId.value = span.id
}

function onSelectSuspect(spanId: string) {
  selectedSpanId.value = spanId
}

const decisionsForSelected = computed(() => {
  if (!trace.value || !selectedSpanId.value) return []
  return trace.value.decisions.filter((d) => d.spanId === selectedSpanId.value)
})

onMounted(async () => {
  try {
    catalog.value = await api.catalog()
    await refreshList()
    if (runs.value.length > 0) {
      await openTrace(runs.value[0].traceId)
    }
  } catch (e) {
    error.value = String(e) + ' —— 确认 8081 服务已启动'
  }
})
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <h1>Agent 可观测台 · Day06</h1>
        <span>20 个决策点 · 三屏定位 · 嫌疑排序(200 条样本 R@3 100%)</span>
      </div>
      <div class="scenario-bar">
        <button
          v-for="s in catalog?.scenarios ?? []"
          :key="s.name"
          class="chip"
          :class="{ primary: s.name === 'NORMAL' }"
          :disabled="runningScenario !== ''"
          :title="s.description"
          @click="runScenario(s.name, catalog?.tasks.find((t) => t.scenario === s.name)?.question ?? '帮我把订单 ORD-100234 的 299 元退款处理一下')"
        >
          <span v-if="runningScenario === s.name" class="spinner"></span>
          {{ s.label }}
        </button>
      </div>
    </header>

    <div class="body">
      <aside class="sidebar">
        <div class="sidebar-head">
          <span>最近运行</span>
          <span class="mono">{{ runs.length }}</span>
        </div>
        <div class="run-list">
          <div
            v-for="r in runs"
            :key="r.traceId"
            class="run-item"
            :class="{ active: r.traceId === trace?.traceId }"
            @click="openTrace(r.traceId)"
          >
            <div class="run-q">{{ r.scenarioLabel }} · {{ r.topSuspect }}</div>
            <div class="run-line">
              <span :class="outcomeClass(r.outcome)">{{ r.outcomeLabel }}</span>
              <span v-if="r.silent" class="badge warn">沉默</span>
              <span class="run-meta">
                {{ fmtMs(r.durationMs) }} · {{ r.tokens }}tok
              </span>
            </div>
          </div>
          <div v-if="runs.length === 0" class="empty">还没有运行记录</div>
        </div>
        <div v-if="overview" class="sidebar-head" style="border-top: 1px solid var(--border)">
          <span>整体口径</span>
        </div>
        <div v-if="overview" style="padding: 0 14px 14px; font-size: 11.5px" class="mono muted">
          <div>成功率 {{ (overview.successRate * 100).toFixed(0) }}% · 沉默 {{ (overview.silentRate * 100).toFixed(0) }}%</div>
          <div>P50 {{ overview.p50Ms.toFixed(0) }}ms · P95 {{ overview.p95Ms.toFixed(0) }}ms</div>
          <div>重试放大 {{ overview.avgRetryAmp.toFixed(2) }}× · {{ overview.avgSpans.toFixed(0) }} span</div>
        </div>
      </aside>

      <main class="main">
        <p v-if="error" class="err" style="margin-bottom: 10px">{{ error }}</p>

        <div class="tabs">
          <button class="tab" :class="{ active: tab === 'locate' }" @click="tab = 'locate'">三屏定位</button>
          <button class="tab" :class="{ active: tab === 'ledger' }" @click="tab = 'ledger'">
            决策台账 <span class="muted">({{ trace?.decisionCount ?? 0 }})</span>
          </button>
          <button class="tab" :class="{ active: tab === 'bench' }" @click="tab = 'bench'">评测</button>
        </div>

        <template v-if="tab === 'locate'">
          <div v-if="!trace" class="card empty">左侧选一次运行,或点右上角任意剧本跑一次</div>
          <template v-else>
            <div class="grid-4">
              <div class="metric">
                <div class="k">结论 / 自检可信度</div>
                <div class="v" :class="{ bad: trace.outcome !== 'SUCCESS' }">{{ trace.outcomeLabel }}</div>
                <div class="s">trust {{ trace.trust.toFixed(2) }} · 重试放大 {{ trace.retryAmplification.toFixed(1) }}×</div>
              </div>
              <div class="metric">
                <div class="k">全程耗时</div>
                <div class="v">{{ fmtMs(trace.durationMs) }}</div>
                <div class="s">{{ trace.spanCount }} 个 Span · {{ trace.decisionCount }} 条决策</div>
              </div>
              <div class="metric">
                <div class="k">Token / 成本</div>
                <div class="v">{{ trace.tokens }}</div>
                <div class="s">{{ fmtCost(trace.costMicros) }}</div>
              </div>
              <div class="metric" :class="{ warn: !trace.sampled }">
                <div class="k">采样</div>
                <div class="v">{{ trace.sampled ? '已上报' : '仅本地' }}</div>
                <div class="s">失败与低可信度必定上报</div>
              </div>
            </div>

            <div class="card" style="margin-top: 12px">
              <div class="card-head">
                <h2>第一屏 · 三个嫌疑</h2>
                <span class="hint">从 {{ trace.spanCount }} 个 Span 里挑出最该看的 3 个,每人一句话证据</span>
              </div>
              <SuspectList
                :suspects="trace.suspects"
                :narrative="trace.narrative"
                @select="onSelectSuspect"
              />
            </div>

            <div class="card">
              <div class="card-head">
                <h2>第二屏 · 瀑布图</h2>
                <span class="hint">红框 = 嫌疑;×n = 尝试次数;完整性标记表示"没有 error 但结果不对"</span>
              </div>
              <Waterfall
                :spans="trace.spans"
                :total-ms="trace.durationMs"
                :suspects="trace.suspects"
                :selected-id="selectedSpanId"
                @select="onSelectSpan"
              />
            </div>

            <div class="card">
              <div class="card-head">
                <h2>第三屏 · 这个 Span 凭什么</h2>
                <span class="hint">属性 + 事件 + 它自己的决策;三者对不上号时,问题就在这里</span>
              </div>
              <div v-if="!selectedSpan" class="empty">点瀑布图上任意一行</div>
              <template v-else>
                <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 8px">
                  <strong class="mono">{{ selectedSpan.name }}</strong>
                  <span class="badge ghost">{{ selectedSpan.kindLabel }}</span>
                  <span :class="selectedSpan.status === 'ERROR' ? 'badge bad' : 'badge ok'">
                    {{ selectedSpan.status }}
                  </span>
                  <span class="mono tiny muted">
                    {{ fmtMs(selectedSpan.durationMs) }} · self {{ fmtMs(selectedSpan.selfMs) }}
                    <template v-if="selectedSpan.attempts > 1"> · 尝试 {{ selectedSpan.attempts }} 次</template>
                  </span>
                  <span v-if="!selectedSpan.integrity" class="badge bad">完整性未通过</span>
                  <span v-if="selectedSpan.redactions > 0" class="badge info">
                    脱敏 {{ selectedSpan.redactions }} 处
                  </span>
                </div>
                <p v-if="selectedSpan.errorMessage" class="err mono tiny">
                  {{ selectedSpan.errorKind }}: {{ selectedSpan.errorMessage }}
                </p>
                <dl class="kv">
                  <template v-for="(v, k) in selectedSpan.attrs" :key="k">
                    <dt>{{ k }}</dt>
                    <dd>{{ typeof v === 'object' ? JSON.stringify(v) : String(v) }}</dd>
                  </template>
                </dl>
                <div v-if="selectedSpan.events.length > 0" style="margin-top: 8px">
                  <div class="tiny muted" style="margin-bottom: 2px">事件</div>
                  <div v-for="(e, i) in selectedSpan.events" :key="i" class="mono tiny">
                    +{{ e.atMs }}ms {{ e.name }} {{ JSON.stringify(e.attrs) }}
                  </div>
                </div>
                <div v-if="decisionsForSelected.length > 0" style="margin-top: 10px">
                  <div class="tiny muted" style="margin-bottom: 4px">该 Span 上的决策</div>
                  <DecisionLedger :decisions="decisionsForSelected" @select="onSelectSuspect" />
                </div>
              </template>
            </div>
          </template>
        </template>

        <template v-else-if="tab === 'ledger'">
          <div class="card">
            <div class="card-head">
              <h2>决策台账 · {{ trace?.question ?? '' }}</h2>
              <span class="hint">20 个决策点的全集,不是每次都会走满</span>
            </div>
            <DecisionLedger
              v-if="trace"
              :decisions="trace.decisions"
              @select="onSelectSuspect"
            />
            <div v-else class="empty">先选一次运行</div>
          </div>
          <div class="card">
            <div class="card-head">
              <h2>回答</h2>
              <span class="hint">用户最终看到的内容</span>
            </div>
            <p style="margin: 0; font-size: 12.5px">{{ trace?.answer }}</p>
          </div>
        </template>

        <template v-else>
          <BenchPanel />
        </template>
      </main>
    </div>
  </div>
</template>
