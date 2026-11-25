<script setup lang="ts">
import { ref } from 'vue'
import HitCard from './HitCard.vue'
import { retrieve } from '../api/client'
import type { Mode, RetrieveResponse } from '../types'

const query = ref('网关流式输出为什么会被缓冲')
const mode = ref<Mode>('full')
const topK = ref(5)
const loading = ref(false)
const error = ref('')
const result = ref<RetrieveResponse | null>(null)

const modes: { value: Mode; label: string }[] = [
  { value: 'bm25', label: '① BM25' },
  { value: 'vector', label: '② 向量' },
  { value: 'hybrid', label: '③ 混合 RRF' },
  { value: 'full', label: '④ 混合+重排' }
]

const examples = [
  '网关流式输出为什么会被缓冲',
  '虚拟线程什么时候反而更慢',
  'limit 翻到第一百万行很慢怎么办',
  'RRF 为什么比加权分数稳定',
  '同一句话问四种模式看排序差异'
]

async function run() {
  if (!query.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    result.value = await retrieve(query.value, mode.value, topK.value)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function pick(text: string) {
  query.value = text
  run()
}
</script>

<template>
  <div class="panel">
    <div class="row">
      <input
        v-model="query"
        class="grow"
        type="text"
        placeholder="输入一个真实用户会问的问题,比如:网关超时怎么配"
        @keyup.enter="run"
      />
      <select v-model="mode">
        <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option>
      </select>
      <select v-model.number="topK">
        <option :value="3">Top 3</option>
        <option :value="5">Top 5</option>
        <option :value="8">Top 8</option>
        <option :value="12">Top 12</option>
      </select>
      <button class="primary" :disabled="loading" @click="run">{{ loading ? '检索中…' : '检索' }}</button>
    </div>
    <div class="examples">
      <button v-for="e in examples" :key="e" class="example" @click="pick(e)">{{ e }}</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
  </div>

  <div v-if="result" class="panel">
    <div class="row" style="justify-content: space-between">
      <div>
        <span class="badge">{{ result.mode }}</span>
        <span class="muted" style="margin-left: 8px">
          {{ result.total }} 条命中 · 耗时 {{ result.tookMs }}ms
        </span>
      </div>
      <span class="muted">同一张卡片上的四路分数,就是这条结果被排上来的全部依据</span>
    </div>
    <HitCard v-for="(hit, i) in result.hits" :key="hit.chunk.id" :hit="hit" :rank="i + 1" />
    <p v-if="result.hits.length === 0" class="empty">没有命中。换个问法,或检查知识库里是否真有相关内容。</p>
  </div>
  <div v-else class="panel empty">输入问题后点检索,先看召回,再谈生成。</div>
</template>
