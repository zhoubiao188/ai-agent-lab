<script setup lang="ts">
import { SLOT_LABEL, type PlanStep, type StepStatus } from '../types'

defineProps<{
  steps: PlanStep[]
  status: Record<string, StepStatus>
  durations: Record<string, number>
  errors: Record<string, string>
  violations: string[]
  missing: string[]
  covered: string[]
}>()

function stepClass(id: string, status: Record<string, StepStatus>) {
  return status[id] ?? 'idle'
}
</script>

<template>
  <div>
    <div class="steps">
      <div v-for="s in steps" :key="s.id" class="step" :class="stepClass(s.id, status)">
        <div class="row1">
          <span class="sid">{{ s.id }}</span>
          <span class="title">{{ s.title }}</span>
          <span class="tool">{{ s.tool }}</span>
          <span v-if="s.write" class="chip write">写</span>
          <span class="ms">
            {{ status[s.id] === 'ok' ? (durations[s.id] ?? 0) + 'ms' : status[s.id] ?? '' }}
          </span>
        </div>
        <div class="row2">
          <span v-for="d in s.dependsOn" :key="d" class="chip">← {{ d }}</span>
          <span v-for="c in s.covers" :key="c" class="chip cover">
            {{ SLOT_LABEL[c] ?? c }}
          </span>
        </div>
        <div v-if="errors[s.id]" class="err">{{ errors[s.id] }}</div>
      </div>
    </div>

    <div class="gaps" v-if="missing.length">
      <div class="title">覆盖核对发现的缺口(金标准里有、计划里没有):</div>
      <span v-for="m in missing" :key="m" class="chip miss">{{ SLOT_LABEL[m] ?? m }}</span>
    </div>

    <div class="gaps" v-if="violations.length">
      <div class="title">静态校验不通过:</div>
      <div v-for="v in violations" :key="v" class="err">{{ v }}</div>
    </div>
  </div>
</template>
