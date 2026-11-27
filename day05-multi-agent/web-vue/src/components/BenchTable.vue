<script setup lang="ts">
import type { BenchReport } from '../types'

defineProps<{ report: BenchReport | null }>()

function pct(v: number) {
  return (v * 100).toFixed(1) + '%'
}
</script>

<template>
  <div v-if="!report" class="hint">还没有跑对照实验。点上面的「跑四组对照」,20 个任务 × 4 组配置大约 30 秒。</div>
  <div v-else>
    <table>
      <thead>
        <tr>
          <th>配置</th>
          <th>成功率</th>
          <th>假成功</th>
          <th>被拦下</th>
          <th>平均步数</th>
          <th>平均延迟</th>
          <th>P95</th>
          <th>重规划</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(r, i) in report.rows" :key="r.policy" :class="{ best: i === report.rows.length - 1 }">
          <td>{{ r.policy }}</td>
          <td class="num">{{ r.success }}/{{ r.total }} ({{ pct(r.successRate) }})</td>
          <td class="num">{{ r.falseSuccess }}</td>
          <td class="num">{{ r.blocked }}</td>
          <td class="num">{{ r.avgSteps }}</td>
          <td class="num">{{ r.avgMs }}ms</td>
          <td class="num">{{ r.p95Ms }}ms</td>
          <td class="num">{{ r.replans }}</td>
        </tr>
      </tbody>
    </table>

    <div class="detail">
      <table>
        <thead>
          <tr>
            <th>任务</th>
            <th>缺陷</th>
            <th v-for="r in report.rows" :key="r.policy">{{ r.policy.split('-')[0] }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in report.details" :key="d.taskId">
            <td class="tag">{{ d.taskId }}</td>
            <td class="tag">{{ d.flaw === 'CLEAN' ? '—' : d.flaw }}</td>
            <td v-for="r in report.rows" :key="r.policy" class="tag">
              {{ d.byPolicy[r.policy] === 'OK' ? '✓' : '✗' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
