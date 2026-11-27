<script setup lang="ts">
import type { SuspectDto } from '../types'

defineProps<{
  suspects: SuspectDto[]
  narrative: string
}>()

const emit = defineEmits<{ (e: 'select', spanId: string): void }>()

const SEGMENTS: { key: string; label: string; color: string }[] = [
  { key: 'selfMs', label: '耗时', color: '#7f56d9' },
  { key: 'error', label: '错误', color: '#f04438' },
  { key: 'retry', label: '重试', color: '#f79009' },
  { key: 'tokens', label: 'Token', color: '#0ba5ec' },
  { key: 'decision', label: '决策', color: '#ee46bc' },
  { key: 'integrity', label: '完整性', color: '#12b76a' }
]

function pct(v: number | undefined): string {
  return Math.round((v ?? 0) * 100) + '%'
}
</script>

<template>
  <div>
    <div class="suspects">
      <div
        v-for="(s, i) in suspects"
        :key="s.spanId"
        class="suspect"
        @click="emit('select', s.spanId)"
      >
        <div class="suspect-rank">#{{ i + 1 }} · {{ s.kindLabel }}<span v-if="s.decisionCode"> · {{ s.decisionCode }}</span></div>
        <div class="suspect-name">{{ s.name }}</div>
        <div class="suspect-score">{{ s.score.toFixed(3) }}<small>分</small></div>
        <div class="stack">
          <i
            v-for="seg in SEGMENTS"
            :key="seg.key"
            :style="{
              width: pct(s.contributions[seg.key]),
              background: seg.color
            }"
            :title="`${seg.label} ${pct(s.contributions[seg.key])}`"
          ></i>
        </div>
        <ul class="evidence">
          <li v-for="(e, idx) in s.evidence" :key="idx">{{ e }}</li>
        </ul>
      </div>
    </div>
    <div v-if="narrative" class="narrative">{{ narrative }}</div>
  </div>
</template>
