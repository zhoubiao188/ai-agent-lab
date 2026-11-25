<script setup lang="ts">
import { ref } from 'vue'
import { streamAsk } from '../api/client'
import type { Citation } from '../types'

const question = ref('Spring Boot 的三级缓存是怎么解决循环依赖的')
const answer = ref('')
const citations = ref<Citation[]>([])
const streaming = ref(false)
const error = ref('')
const meta = ref('')
let controller: AbortController | null = null

async function ask() {
  if (!question.value.trim() || streaming.value) return
  streaming.value = true
  answer.value = ''
  citations.value = []
  error.value = ''
  meta.value = ''
  controller = new AbortController()
  try {
    await streamAsk(
      question.value,
      'full',
      4,
      {
        onCitations: (list) => (citations.value = list),
        onDelta: (text) => (answer.value += text),
        onDone: (p) => {
          meta.value = `检索 ${p.retrieveMs}ms · 总计 ${p.totalMs}ms · ${p.model}`
        },
        onError: (m) => (error.value = m)
      },
      controller.signal
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    streaming.value = false
  }
}

function stop() {
  controller?.abort()
  streaming.value = false
}
</script>

<template>
  <div class="panel">
    <div class="row">
      <input
        v-model="question"
        class="grow"
        type="text"
        placeholder="问一个知识库里应该有答案的问题"
        @keyup.enter="ask"
      />
      <button class="primary" :disabled="streaming" @click="ask">{{ streaming ? '生成中…' : '提问' }}</button>
      <button v-if="streaming" class="ghost" @click="stop">中断</button>
    </div>
    <p class="muted" style="margin: 8px 0 0">
      先推引用、再推正文。引用一到位前端就能显示"正在查阅哪几篇文档",等待感直接减半。
    </p>
  </div>

  <div v-if="citations.length" class="panel">
    <div class="row" style="justify-content: space-between">
      <strong style="font-size: 13px">引用片段</strong>
      <span class="muted">{{ meta }}</span>
    </div>
    <div class="spacer"></div>
    <div v-for="c in citations" :key="c.chunkId" class="cite">
      <div class="row" style="gap: 6px">
        <span class="badge">[{{ c.index }}]</span>
        <span class="doc">{{ c.title }}</span>
        <span class="section">/ {{ c.section }}</span>
        <span class="badge gray">{{ c.score }}</span>
      </div>
      <p class="text">{{ c.snippet }}</p>
    </div>
  </div>

  <div v-if="answer || streaming || error" class="panel">
    <strong style="font-size: 13px">回答</strong>
    <p v-if="error" class="error">{{ error }}</p>
    <p class="answer" style="margin-top: 8px">{{ answer }}<span v-if="streaming">▍</span></p>
  </div>
</template>
