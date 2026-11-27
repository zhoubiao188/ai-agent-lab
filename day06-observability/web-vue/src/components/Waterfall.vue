<script setup lang="ts">
import { computed } from 'vue'
import type { SpanDto, SuspectDto } from '../types'

const props = defineProps<{
  spans: SpanDto[]
  totalMs: number
  suspects: SuspectDto[]
  selectedId?: string
}>()

const emit = defineEmits<{ (e: 'select', span: SpanDto): void }>()

const KIND_COLOR: Record<string, string> = {
  RUN: '#667085',
  STEP: '#7f56d9',
  LLM: '#0ba5ec',
  TOOL: '#12b76a',
  WRITE: '#f79009',
  RETRY: '#f04438',
  DECISION: '#98a2b3',
  VALIDATE: '#2e90fa',
  INTERNAL: '#98a2b3'
}

interface Row {
  span: SpanDto
  depth: number
  suspect: boolean
  left: number
  width: number
}

const suspectIds = computed(() => new Set(props.suspects.map((s) => s.spanId)))

const rows = computed<Row[]>(() => {
  const byId = new Map(props.spans.map((s) => [s.id, s]))
  const depthOf = (span: SpanDto): number => {
    let depth = 0
    let parent = span.parentId ? byId.get(span.parentId) : undefined
    while (parent) {
      depth += 1
      parent = parent.parentId ? byId.get(parent.parentId) : undefined
    }
    return depth
  }
  const total = Math.max(props.totalMs, 1)
  return props.spans.map((span) => ({
    span,
    depth: depthOf(span),
    suspect: suspectIds.value.has(span.id),
    left: Math.max(0, Math.min(100, (span.startMs / total) * 100)),
    width: Math.max(0.4, Math.min(100, (span.durationMs / total) * 100))
  }))
})

function fmt(ms: number): string {
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms.toFixed(ms < 10 ? 2 : 0) + 'ms'
}

function color(kind: string): string {
  return KIND_COLOR[kind] ?? '#98a2b3'
}
</script>

<template>
  <div>
    <div class="wf-head waterfall">
      <span>Span(按父子缩进)</span>
      <span>时间轴 · 全程 {{ fmt(totalMs) }}</span>
      <span style="text-align: right">耗时</span>
    </div>
    <div class="waterfall">
      <div
        v-for="row in rows"
        :key="row.span.id"
        class="wf-row"
        :class="{ selected: row.span.id === selectedId, 'is-error': row.span.status === 'ERROR' }"
        @click="emit('select', row.span)"
      >
        <div class="wf-name" :style="{ paddingLeft: row.depth * 10 + 'px' }">
          <span class="kind-dot" :style="{ background: color(row.span.kind) }"></span>
          <span style="overflow: hidden; text-overflow: ellipsis">{{ row.span.name }}</span>
          <span v-if="row.span.attempts > 1" class="badge warn" style="padding: 0 4px">×{{ row.span.attempts }}</span>
          <span v-if="!row.span.integrity" class="badge bad" style="padding: 0 4px">完整性</span>
          <span v-if="row.suspect" class="badge bad" style="padding: 0 4px">嫌疑</span>
        </div>
        <div class="wf-track">
          <div
            class="wf-bar"
            :class="{ suspect: row.suspect, point: row.span.durationMs < 0.5 }"
            :style="{
              left: row.left + '%',
              width: row.span.durationMs < 0.5 ? '3px' : row.width + '%',
              background: color(row.span.kind)
            }"
            :title="`${row.span.name} · ${fmt(row.span.durationMs)} · self ${fmt(row.span.selfMs)}`"
          ></div>
        </div>
        <div class="wf-dur">{{ fmt(row.span.durationMs) }}</div>
      </div>
    </div>
  </div>
</template>
