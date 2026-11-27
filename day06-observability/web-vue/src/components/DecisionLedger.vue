<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DecisionDto } from '../types'

const props = defineProps<{
  decisions: DecisionDto[]
  filter?: string
}>()

const emit = defineEmits<{ (e: 'select', spanId: string): void }>()

const onlyRisky = ref(false)

const list = computed(() => {
  const base = props.filter
    ? props.decisions.filter((d) => d.spanId === props.filter || d.code === props.filter)
    : props.decisions
  return onlyRisky.value ? base.filter((d) => d.confidence < 0.6 || d.margin < 0.1) : base
})

function confClass(v: number): string {
  return v < 0.6 ? 'conf low' : 'conf'
}
</script>

<template>
  <div>
    <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 8px">
      <span class="tiny muted">
        共 {{ list.length }} 条 · 每条都保留"被否掉的选项",否则台账就退化成日志
      </span>
      <label class="tiny muted" style="margin-left: auto; cursor: pointer">
        <input v-model="onlyRisky" type="checkbox" style="vertical-align: -1px" />
        只看可疑(置信度 &lt; 0.6 或差距 &lt; 0.1)
      </label>
    </div>
    <div v-if="list.length === 0" class="empty">没有符合条件的决策</div>
    <div
      v-for="d in list"
      :key="d.code + d.seq"
      class="decision"
      style="cursor: pointer"
      @click="emit('select', d.spanId)"
    >
      <div class="decision-top">
        <span class="decision-code">{{ d.code }}</span>
        <span class="decision-title">{{ d.title }}</span>
        <span class="badge ghost">{{ d.basisLabel }}</span>
        <span class="mono tiny muted">置信度 {{ d.confidence.toFixed(2) }}</span>
        <span :class="confClass(d.confidence)"><i :style="{ width: d.confidence * 100 + '%' }"></i></span>
        <span v-if="d.margin < 0.1" class="badge warn">与次优几乎同分</span>
      </div>
      <div class="decision-chosen">→ {{ d.chosen }}</div>
      <div class="decision-sub">{{ d.question }}<span v-if="d.symptom"> · 出错时:{{ d.symptom }}</span></div>
      <div class="alts">
        <span
          v-for="a in d.alternatives"
          :key="a.name"
          class="alt"
          :class="{ chosen: a.name === d.chosen }"
          :title="a.reason"
        >
          {{ a.name }} · {{ a.score.toFixed(2) }}
        </span>
      </div>
    </div>
  </div>
</template>
