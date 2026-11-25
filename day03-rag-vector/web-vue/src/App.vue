<script setup lang="ts">
import { onMounted, ref } from 'vue'
import RetrievePanel from './components/RetrievePanel.vue'
import EvalPanel from './components/EvalPanel.vue'
import AskPanel from './components/AskPanel.vue'
import { getStats } from './api/client'
import type { KbStats } from './types'

const tab = ref<'retrieve' | 'eval' | 'ask'>('retrieve')
const stats = ref<KbStats | null>(null)

onMounted(async () => {
  try {
    stats.value = await getStats()
  } catch {
    stats.value = null
  }
})
</script>

<template>
  <div class="app">
    <div class="header">
      <div>
        <h1 class="title">RAG 检索控制台</h1>
        <p class="subtitle">
          分块 → 双路召回 → RRF 融合 → 特征重排 → 引用式生成,全链路可观测
        </p>
      </div>
      <span class="badge gray">Vue 3 + Vite</span>
    </div>

    <div class="metrics">
      <div class="metric">
        <div class="k">文档 / 块</div>
        <div class="v">{{ stats ? `${stats.docs} / ${stats.chunks}` : '—' }}</div>
      </div>
      <div class="metric">
        <div class="k">倒排词表</div>
        <div class="v">{{ stats?.vocab ?? '—' }}</div>
      </div>
      <div class="metric">
        <div class="k">向量维度</div>
        <div class="v">{{ stats?.dim ?? '—' }}</div>
      </div>
      <div class="metric">
        <div class="k">向量索引</div>
        <div class="v">{{ stats?.indexType ?? '—' }}</div>
      </div>
      <div class="metric">
        <div class="k">建索引耗时</div>
        <div class="v">{{ stats ? `${stats.buildMillis} ms` : '—' }}</div>
      </div>
      <div class="metric">
        <div class="k">向量缓存命中率</div>
        <div class="v">{{ stats ? `${(stats.cacheHitRate * 100).toFixed(0)}%` : '—' }}</div>
      </div>
    </div>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'retrieve' }" @click="tab = 'retrieve'">检索调试</button>
      <button class="tab" :class="{ active: tab === 'eval' }" @click="tab = 'eval'">评测看板</button>
      <button class="tab" :class="{ active: tab === 'ask' }" @click="tab = 'ask'">引用式问答</button>
    </div>

    <RetrievePanel v-if="tab === 'retrieve'" />
    <EvalPanel v-else-if="tab === 'eval'" />
    <AskPanel v-else />
  </div>
</template>
