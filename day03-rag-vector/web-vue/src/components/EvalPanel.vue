<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getEvalReport } from '../api/client'
import type { EvalReport } from '../types'

const report = ref<EvalReport | null>(null)
const loading = ref(false)
const error = ref('')

const failures = computed(() => {
  if (!report.value) return []
  return report.value.details
    .map((d) => ({ ...d, fullRank: d.ranks['FULL'] ?? -1 }))
    .filter((d) => d.fullRank < 0 || d.fullRank > 5)
})

async function load(force = false) {
  loading.value = true
  error.value = ''
  try {
    report.value = await getEvalReport(force)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

function cls(v: number): string {
  return v > 0 ? 'delta up' : v < 0 ? 'delta down' : 'delta'
}

function signed(v: number): string {
  return (v > 0 ? '+' : '') + v.toFixed(3)
}
</script>

<template>
  <div class="panel">
    <div class="row" style="justify-content: space-between">
      <div>
        <strong>召回消融实验</strong>
        <p class="muted" style="margin: 4px 0 0">
          同一批样本、同一套语料,只换检索策略。改动任何参数之前先跑这一张表。
        </p>
      </div>
      <button class="ghost" :disabled="loading" @click="load(true)">{{ loading ? '评测中…' : '重跑评测' }}</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="report">
      <p class="muted" style="margin-top: 12px">
        {{ report.docs }} 篇文档 / {{ report.chunks }} 块 / {{ report.cases }} 条样本 ·
        向量模型 {{ report.embeddingModel }} · 索引 {{ report.indexType }} ·
        生成于 {{ report.generatedAt }}({{ report.elapsedMs }}ms)
      </p>
      <table>
        <thead>
          <tr>
            <th>策略</th>
            <th class="num">Recall@1</th>
            <th class="num">Recall@3</th>
            <th class="num">Recall@5</th>
            <th class="num">MRR</th>
            <th class="num">NDCG@5</th>
            <th class="num">P50</th>
            <th class="num">P95</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in report.strategies" :key="s.mode">
            <td>{{ s.label }}</td>
            <td class="num">{{ s.recall1.toFixed(3) }}</td>
            <td class="num">{{ s.recall3.toFixed(3) }}</td>
            <td class="num">{{ s.recall5.toFixed(3) }}</td>
            <td class="num">{{ s.mrr.toFixed(3) }}</td>
            <td class="num">{{ s.ndcg5.toFixed(3) }}</td>
            <td class="num">{{ s.p50Micros }}μs</td>
            <td class="num">{{ s.p95Micros }}μs</td>
          </tr>
        </tbody>
      </table>

      <div class="spacer"></div>
      <strong style="font-size: 13px">按查询类型拆解(Recall@5)</strong>
      <table>
        <thead>
          <tr>
            <th>策略</th>
            <th v-for="(_, type) in report.strategies[0].byType" :key="type" class="num">{{ type }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in report.strategies" :key="s.mode">
            <td>{{ s.label }}</td>
            <td v-for="(stat, type) in s.byType" :key="type" class="num">{{ stat.recall5.toFixed(2) }}</td>
          </tr>
        </tbody>
      </table>

      <div class="spacer"></div>
      <strong style="font-size: 13px">策略增量</strong>
      <table>
        <thead>
          <tr>
            <th>变化</th>
            <th class="num">Recall@5</th>
            <th class="num">MRR</th>
            <th class="num">NDCG@5</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in report.deltas" :key="d.from + d.to">
            <td>{{ d.from }} → {{ d.to }}</td>
            <td class="num" :class="cls(d.recall5)">{{ signed(d.recall5) }}</td>
            <td class="num" :class="cls(d.mrr)">{{ signed(d.mrr) }}</td>
            <td class="num" :class="cls(d.ndcg5)">{{ signed(d.ndcg5) }}</td>
          </tr>
        </tbody>
      </table>

      <div class="spacer"></div>
      <strong style="font-size: 13px">失败样本(Top-5 未命中)</strong>
      <p v-if="failures.length === 0" class="muted">没有失败样本。这种情况通常说明评测集太简单,该加难度了。</p>
      <table v-else>
        <thead>
          <tr>
            <th>#</th>
            <th>类型</th>
            <th>查询</th>
            <th class="num">FULL 名次</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in failures" :key="f.id">
            <td>{{ f.id }}</td>
            <td>{{ f.type }}</td>
            <td style="white-space: normal">{{ f.query }}</td>
            <td class="num">{{ f.fullRank > 0 ? f.fullRank : '未召回' }}</td>
          </tr>
        </tbody>
      </table>
    </template>
    <p v-else-if="!loading" class="empty">还没有评测数据。</p>
  </div>
</template>
