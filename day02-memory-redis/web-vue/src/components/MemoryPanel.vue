<script setup lang="ts">
import { computed } from 'vue'
import type { MemorySnapshot, RecallHit } from '../api/agent'

const props = defineProps<{
  memory: MemorySnapshot | null
  recallHits: RecallHit[]
  archivedCount: number
  compacting: boolean
}>()

const emit = defineEmits<{
  (e: 'compact'): void
  (e: 'refresh'): void
}>()

const used = computed(() => props.memory?.tokensUsed ?? 0)
const budget = computed(() => props.memory?.budgetTokens ?? 6000)
const percent = computed(() => (budget.value ? Math.min(100, Math.round((used.value / budget.value) * 100)) : 0))
const level = computed(() => (percent.value > 85 ? 'danger' : percent.value > 60 ? 'warn' : ''))

const saved = computed(() => props.memory?.tokensSaved ?? 0)
const compactRuns = computed(() => props.memory?.compactRuns ?? 0)
const summary = computed(() => props.memory?.summaryPreview || '')
const facts = computed(() => props.memory?.pinnedFacts ?? [])
</script>

<template>
  <div class="memory-body">
    <div>
      <div class="section-title">
        <span>上下文水位</span>
        <span>{{ percent }}%</span>
      </div>
      <div class="meter-bar" style="margin: 6px 0 8px">
        <div class="meter-fill" :class="level" :style="{ width: percent + '%' }"></div>
      </div>
      <div class="metric-grid">
        <div class="metric">
          <div class="k">当前占用</div>
          <div class="v">{{ used }}<small>/ {{ budget }} token</small></div>
        </div>
        <div class="metric">
          <div class="k">摘要占用</div>
          <div class="v">{{ memory?.summaryTokens ?? 0 }}<small>token</small></div>
        </div>
        <div class="metric">
          <div class="k">压缩次数</div>
          <div class="v">{{ compactRuns }}</div>
        </div>
        <div class="metric">
          <div class="k">估算省下</div>
          <div class="v">{{ saved }}<small>token</small></div>
        </div>
      </div>
    </div>

    <div>
      <div class="section-title">
        <span>长期记忆摘要</span>
        <button class="link-btn" :disabled="compacting" @click="emit('compact')">
          {{ compacting ? '压缩中…' : '立即压缩' }}
        </button>
      </div>
      <div class="summary-box" :class="{ empty: !summary }">
        {{ summary || '还没有触发压缩。当上下文超过预算的 72%,最早的整轮对话会被折叠成摘要。' }}
      </div>
    </div>

    <div>
      <div class="section-title">
        <span>关键事实(原文保真)</span>
        <span>{{ facts.length }}</span>
      </div>
      <div v-if="facts.length" class="chips">
        <span v-for="f in facts" :key="f" class="chip">{{ f }}</span>
      </div>
      <div v-else class="memory-empty">
        单号、金额、日期这类信息在压缩时由正则单独抽取,不交给模型改写。
      </div>
    </div>

    <div>
      <div class="section-title">
        <span>归档召回</span>
        <span>{{ archivedCount }} 条</span>
      </div>
      <div v-if="recallHits.length">
        <div v-for="h in recallHits" :key="h.startSeq" class="recall-item">
          <b>seq {{ h.startSeq }}-{{ h.endSeq }} · {{ h.score.toFixed(2) }}</b>
          <div>{{ h.text }}</div>
        </div>
      </div>
      <div v-else class="memory-empty">
        压缩后的原文留在归档里。当你的问题与某段历史相关时,会被 BM25 打分召回并注入上下文。
      </div>
    </div>

    <button class="link-btn" @click="emit('refresh')">刷新记忆状态</button>
  </div>
</template>
