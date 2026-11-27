<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import type { BenchReport } from '../types'

const report = ref<BenchReport | null>(null)
const loading = ref(false)
const error = ref('')
const incidents = ref(200)

async function run() {
  loading.value = true
  error.value = ''
  try {
    report.value = await api.bench(incidents.value)
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function pct(v: number): string {
  return (v * 100).toFixed(0) + '%'
}
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <h2>嫌疑排序评测</h2>
        <span class="hint">
          注入确定性故障,看排序能不能把真正的埋点排进前三。标准答案只有一份,算法拿不到。
        </span>
      </div>
      <div style="display: flex; gap: 8px; align-items: center">
        <label class="tiny muted">样本数</label>
        <input
          v-model.number="incidents"
          type="number"
          min="5"
          max="1000"
          class="mono"
          style="width: 80px; padding: 4px 6px; border: 1px solid var(--border-strong); border-radius: 6px"
        />
        <button class="chip primary" :disabled="loading" @click="run">
          <span v-if="loading" class="spinner"></span>
          {{ loading ? ' 评测中' : '开始评测' }}
        </button>
        <span v-if="report" class="tiny muted">seed {{ report.seed }} · {{ report.generatedAt }}</span>
      </div>
      <p v-if="error" class="err">{{ error }}</p>
    </div>

    <template v-if="report">
      <div class="grid-4" style="margin-top: 12px">
        <div class="metric">
          <div class="k">Recall@1</div>
          <div class="v">{{ pct(report.overall.recall1) }}</div>
          <div class="s">第一名就是根因</div>
        </div>
        <div class="metric">
          <div class="k">Recall@3</div>
          <div class="v">{{ pct(report.overall.recall3) }}</div>
          <div class="s">前三名命中根因</div>
        </div>
        <div class="metric">
          <div class="k">平均名次</div>
          <div class="v">{{ report.overall.avgRank.toFixed(2) }}</div>
          <div class="s">MRR {{ report.overall.mrr.toFixed(2) }}</div>
        </div>
        <div class="metric">
          <div class="k">阅读量</div>
          <div class="v">{{ report.overall.avgSpans.toFixed(0) }} → 3</div>
          <div class="s">平均 {{ report.overall.avgDecisions.toFixed(0) }} 条决策</div>
        </div>
      </div>

      <div class="card" style="margin-top: 12px">
        <div class="card-head">
          <h2>分场景</h2>
          <span class="hint">{{ report.note }}</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>剧本</th>
              <th class="num">样本</th>
              <th class="num">R@1</th>
              <th class="num">R@3</th>
              <th class="num">MRR</th>
              <th class="num">平均名次</th>
              <th class="num">Span</th>
              <th class="num">决策</th>
              <th class="num">最高分</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in report.families" :key="f.scenario">
              <td><strong>{{ f.label }}</strong> <span class="muted tiny">{{ f.scenario }}</span></td>
              <td class="num">{{ f.incidents }}</td>
              <td class="num">{{ pct(f.recall1) }}</td>
              <td class="num">{{ pct(f.recall3) }}</td>
              <td class="num">{{ f.mrr.toFixed(2) }}</td>
              <td class="num">{{ f.avgRank.toFixed(1) }}</td>
              <td class="num">{{ f.avgSpans.toFixed(0) }}</td>
              <td class="num">{{ f.avgDecisions.toFixed(0) }}</td>
              <td class="num">{{ f.avgTopScore.toFixed(3) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <div class="card-head">
          <h2>与三个单点基线对比</h2>
          <span class="hint">只报"我们 92%"没有意义,必须给出朴素做法的成绩</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>做法</th>
              <th>说明</th>
              <th class="num">R@1</th>
              <th class="num">R@3</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><strong>复合排序(本文)</strong></td>
              <td class="muted tiny">耗时 + 错误 + 重试 + Token + 决策 + 完整性</td>
              <td class="num">{{ pct(report.overall.recall1) }}</td>
              <td class="num">{{ pct(report.overall.recall3) }}</td>
            </tr>
            <tr v-for="b in report.baselines" :key="b.name">
              <td>{{ b.name }}</td>
              <td class="muted tiny">{{ b.description }}</td>
              <td class="num">{{ pct(b.recall1) }}</td>
              <td class="num">{{ pct(b.recall3) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
