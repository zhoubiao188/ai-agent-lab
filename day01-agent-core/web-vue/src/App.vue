<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import MessageBubble from './components/MessageBubble.vue'
import { useAgent } from './composables/useAgent'

const { messages, busy, info, infoError, loadInfo, send, abort, reset } = useAgent()

const input = ref('')
const scroller = ref<HTMLElement | null>(null)

const presets = [
  '北京今天天气怎么样?顺便帮我算一下 (128.5 + 71.5) * 3 / 2',
  '退货的运费谁出?发票抬头能改吗?',
  '现在几点了,距离今天结束还有多少小时',
  '你们的会员等级怎么升级,我要转人工投诉',
]

async function submit() {
  const text = input.value
  if (!text.trim() || busy.value) return
  input.value = ''
  await send(text)
  scrollToBottom()
}

function usePreset(p: string) {
  input.value = p
  void submit()
}

function onKeydown(e: KeyboardEvent) {
  // Enter 发送,Shift+Enter 换行 —— 输入法组合状态下不拦截
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    void submit()
  }
}

function scrollToBottom() {
  void nextTick(() => {
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(
  () => messages.value.map((m) => m.text.length + m.tools.length).join(','),
  scrollToBottom,
)

onMounted(async () => {
  await loadInfo()
  await nextTick()
  scrollToBottom()
})
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <span class="logo">◈</span>
        <div>
          <h1>AI Agent 控制台</h1>
          <p>Spring Boot 3 · Spring Cloud Gateway · Vue 3 · SSE · Function Calling</p>
        </div>
      </div>
      <div class="status">
        <template v-if="info">
          <span class="pill" :class="info.realModel ? 'live' : 'mock'">
            {{ info.realModel ? '真实模型' : '离线演示' }} · {{ info.provider }}
          </span>
          <span class="pill ghost">{{ info.model }}</span>
          <span class="pill ghost">{{ info.tools?.length ?? 0 }} 个工具</span>
        </template>
        <span v-else-if="infoError" class="pill err">后端未连接</span>
        <button class="link" @click="reset">清空会话</button>
      </div>
    </header>

    <main ref="scroller" class="stream">
      <div v-if="!messages.length" class="welcome">
        <h2>手写一个会用工具的 Agent</h2>
        <p>整条链路都是真实运行的:LLM 流式输出 → 工具调用 → 结果回填 → 二次生成。</p>
        <div class="presets">
          <button v-for="p in presets" :key="p" class="preset" @click="usePreset(p)">{{ p }}</button>
        </div>
        <p v-if="infoError" class="hint">
          提示:先启动 <code>agent-service</code>(8081)与 <code>gateway</code>(8080),再刷新本页。
        </p>
      </div>

      <MessageBubble v-for="m in messages" :key="m.id" :msg="m" />
    </main>

    <footer class="composer">
      <textarea
        v-model="input"
        rows="1"
        placeholder="问点什么…(Enter 发送 / Shift+Enter 换行)"
        @keydown="onKeydown"
      ></textarea>
      <button v-if="busy" class="send stop" @click="abort">停止</button>
      <button v-else class="send" :disabled="!input.trim()" @click="submit">发送</button>
    </footer>
  </div>
</template>
