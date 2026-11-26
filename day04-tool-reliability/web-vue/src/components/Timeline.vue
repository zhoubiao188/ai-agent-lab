<script setup lang="ts">
import type { Lane } from '../types'

const props = defineProps<{ lanes: Lane[]; total: number }>()

function left(l: Lane): number {
  return Math.min(99, (l.start / Math.max(props.total, 1)) * 100)
}

function width(l: Lane): number {
  const end = l.end ?? props.total
  const w = ((end - l.start) / Math.max(props.total, 1)) * 100
  return Math.max(1.5, Math.min(100, w))
}

function tip(l: Lane): string {
  const parts = [`${l.tool} · ${l.status}`]
  if (l.attempts > 1) parts.push(`尝试 ${l.attempts} 次`)
  if (l.retries > 0) parts.push(`重试 ${l.retries} 次`)
  if (l.repairs.length > 0) parts.push(`修复 ${l.repairs.length} 处参数`)
  if (l.error) parts.push(l.error)
  return parts.join('\n')
}
</script>

<template>
  <div>
    <div v-for="l in lanes" :key="l.id" class="lane">
      <div class="name" :title="l.id">
        {{ l.tool }}
        <span v-if="l.dependsOn.length" class="dep">← {{ l.dependsOn.join(',') }}</span>
      </div>
      <div class="track">
        <div
          class="bar"
          :class="l.status"
          :style="{ left: left(l) + '%', width: width(l) + '%' }"
          :title="tip(l)"
        />
      </div>
      <div class="meta">
        {{ l.end == null ? '…' : Math.round(l.end - l.start) + 'ms' }}
      </div>
    </div>
    <p v-if="lanes.length === 0" class="hint">还没有调用。点"运行"看一条真实的时间线。</p>
  </div>
</template>
