<script setup lang="ts">
import type { BenchReport } from '../types'

defineProps<{ report: BenchReport | null; loading: boolean }>()
</script>

<template>
  <div>
    <p v-if="!report && !loading" class="hint">
      四组配置跑同一批任务、同一批故障序列,只换可靠性开关。大约 30 秒。
    </p>
    <p v-if="loading" class="hint">正在跑评测:每组 120 个任务,共 4 组…</p>

    <table v-if="report" class="bench">
      <thead>
        <tr>
          <th>配置</th>
          <th>成功率</th>
          <th>降级</th>
          <th>失败</th>
          <th>均值</th>
          <th>P95</th>
          <th>重试</th>
          <th>修复</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in report.rows" :key="row.policy">
          <td>{{ row.policy }}</td>
          <td class="num">
            <div class="bar-cell">
              <div class="fill" :style="{ width: Math.round(row.successRate * 60) + 'px' }" />
              <span>{{ (row.successRate * 100).toFixed(1) }}%</span>
            </div>
          </td>
          <td class="num">{{ row.degraded }}</td>
          <td class="num">{{ row.failed }}</td>
          <td class="num">{{ row.avgMs }}ms</td>
          <td class="num">{{ row.p95Ms }}ms</td>
          <td class="num">{{ row.retries }}</td>
          <td class="num">{{ row.repairs }}</td>
        </tr>
      </tbody>
    </table>

    <div v-if="report" class="hint">
      任务数 {{ report.tasks }} · seed {{ report.seed }} · 延迟压到
      {{ Math.round(report.latencyScale * 100) }}% · 总耗时
      {{ (report.totalMs / 1000).toFixed(1) }}s
    </div>
  </div>
</template>
