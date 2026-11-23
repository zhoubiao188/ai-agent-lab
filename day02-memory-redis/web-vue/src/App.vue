<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import ConversationList from './components/ConversationList.vue'
import MemoryPanel from './components/MemoryPanel.vue'
import MessageItem from './components/MessageItem.vue'
import {
  api,
  streamChat,
  type AgentInfo,
  type ConversationDetail,
  type ConversationSummary,
  type MemorySnapshot,
  type MemoryStats,
  type RecallHit,
  type StreamEvent,
} from './api/agent'
import { uid, type UiMessage } from './types'

const userId = 'demo-user'

const info = ref<AgentInfo | null>(null)
const stats = ref<MemoryStats | null>(null)
const conversations = ref<ConversationSummary[]>([])
const currentId = ref<string | null>(null)
const messages = ref<UiMessage[]>([])
const memory = ref<MemorySnapshot | null>(null)
const recallHits = ref<RecallHit[]>([])
const archivedCount = ref(0)
const input = ref('')
const sending = ref(false)
const compacting = ref(false)
const toast = ref('')
const listEl = ref<HTMLElement | null>(null)

const currentTitle = computed(() => {
  const hit = conversations.value.find((c) => c.id === currentId.value)
  return hit?.title || (messages.value.length ? '未命名会话' : '新对话')
})

const percent = computed(() => {
  const used = memory.value?.tokensUsed ?? 0
  const budget = memory.value?.budgetTokens ?? info.value?.memory.budgetTokens ?? 6000
  return budget ? Math.min(100, Math.round((used / budget) * 100)) : 0
})

const meterLevel = computed(() => (percent.value > 85 ? 'danger' : percent.value > 60 ? 'warn' : ''))

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) {
    listEl.value.scrollTop = listEl.value.scrollHeight
  }
}

function showToast(text: string) {
  toast.value = text
  window.setTimeout(() => {
    if (toast.value === text) toast.value = ''
  }, 4200)
}

async function refreshList() {
  try {
    conversations.value = await api.listConversations(userId)
  } catch (e) {
    console.warn('拉取会话列表失败', e)
  }
}

function snapshotFromDetail(d: ConversationDetail): MemorySnapshot {
  return {
    tokensUsed: d.tokensUsed,
    budgetTokens: d.budgetTokens,
    summaryTokens: d.summaryTokens,
    historyMessages: d.messages.length,
    summaryCoveredSeq: d.summaryCoveredSeq,
    compactRuns: d.stats.compactRuns,
    archivedMessages: d.stats.archivedMessages,
    tokensSaved: d.stats.tokensSavedEstimate,
    pinnedFacts: d.pinnedFacts || [],
    summaryPreview: d.summary || '',
  }
}

function toUiMessages(d: ConversationDetail): UiMessage[] {
  const out: UiMessage[] = []
  for (const m of d.messages) {
    if (m.role === 'user') {
      out.push({ key: uid('u'), role: 'user', content: m.content || '' })
    } else if (m.role === 'tool') {
      out.push({
        key: uid('t'),
        role: 'tool',
        content: m.content || '',
        toolName: m.name,
        success: !(m.content || '').startsWith('ERROR:'),
      })
    } else if (m.role === 'assistant') {
      // 只带工具调用的过渡语不必展示(界面上用工具卡片代替)
      if (m.content && m.content.trim()) {
        out.push({ key: uid('a'), role: 'assistant', content: m.content })
      }
    }
  }
  return out
}

async function openConversation(id: string) {
  if (sending.value) return
  currentId.value = id
  recallHits.value = []
  try {
    const d = await api.detail(id)
    messages.value = toUiMessages(d)
    memory.value = snapshotFromDetail(d)
    archivedCount.value = d.archivedCount
    await scrollToBottom()
  } catch (e) {
    showToast(`打开会话失败:${(e as Error).message}`)
  }
}

function newConversation() {
  if (sending.value) return
  currentId.value = null
  messages.value = []
  memory.value = null
  recallHits.value = []
  archivedCount.value = 0
}

async function refreshMemory() {
  if (!currentId.value) return
  try {
    const d = await api.detail(currentId.value)
    memory.value = snapshotFromDetail(d)
    archivedCount.value = d.archivedCount
  } catch {
    /* 忽略 */
  }
}

async function refreshStats() {
  try {
    stats.value = await api.stats()
  } catch {
    /* 统计失败不影响使用 */
  }
}

async function doCompact() {
  if (!currentId.value || compacting.value) return
  compacting.value = true
  try {
    const r = await api.compact(currentId.value)
    showToast(
      r.compacted
        ? `已压缩:${r.beforeTokens} → ${r.afterTokens} token(${r.mode})`
        : `本次没有压缩(${r.mode})`,
    )
    await openConversation(currentId.value)
    await refreshList()
  } catch (e) {
    showToast(`压缩失败:${(e as Error).message}`)
  } finally {
    compacting.value = false
  }
}

async function removeConversation(id: string) {
  try {
    await api.remove(id)
    if (currentId.value === id) newConversation()
    await refreshList()
  } catch (e) {
    showToast(`删除失败:${(e as Error).message}`)
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return

  input.value = ''
  sending.value = true
  recallHits.value = []
  messages.value.push({ key: uid('u'), role: 'user', content: text })
  await scrollToBottom()

  const conversationId = currentId.value ?? crypto.randomUUID()
  currentId.value = conversationId

  let assistantKey = ''
  let hasAssistant = false
  let lastToolKey = ''

  const ensureAssistant = (): UiMessage => {
    if (!hasAssistant) {
      assistantKey = uid('a')
      messages.value.push({
        key: assistantKey,
        role: 'assistant',
        content: '',
        streaming: true,
      })
      hasAssistant = true
    }
    return messages.value[messages.value.length - 1]
  }

  const handle = (e: StreamEvent) => {
    switch (e.name) {
      case 'message_start':
        if (e.data.memory) memory.value = e.data.memory as MemorySnapshot
        break
      case 'delta': {
        const bubble = ensureAssistant()
        bubble.content += String(e.data.text ?? '')
        void scrollToBottom()
        break
      }
      case 'delta_reset': {
        // 模型先说了句"我查一下"再调工具,这段过渡语要撤回
        const bubble = ensureAssistant()
        bubble.content = ''
        break
      }
      case 'tool_start': {
        // 过渡语清空后,后续答案要新起一个气泡,顺序才是「卡片 → 答案」
        if (hasAssistant) {
          const last = messages.value[messages.value.length - 1]
          if (last && last.role === 'assistant' && !last.content.trim()) {
            messages.value.pop()
          }
          hasAssistant = false
        }
        lastToolKey = uid('t')
        messages.value.push({
          key: lastToolKey,
          role: 'tool',
          toolName: String(e.data.name ?? 'tool'),
          content: '',
          streaming: true,
        })
        void scrollToBottom()
        break
      }
      case 'tool_result': {
        const card = messages.value.find((m) => m.key === lastToolKey)
        if (card) {
          card.content = String(e.data.content ?? '')
          card.toolArgs = String(e.data.arguments ?? '')
          card.success = Boolean(e.data.success)
          card.elapsedMs = Number(e.data.elapsedMs ?? 0)
          card.streaming = false
        }
        void scrollToBottom()
        break
      }
      case 'memory_compacted': {
        const before = Number(e.data.beforeTokens ?? 0)
        const after = Number(e.data.afterTokens ?? 0)
        const folded = Number(e.data.foldedMessages ?? 0)
        showToast(`记忆压缩:折叠 ${folded} 条消息,${before} → ${after} token(${e.data.mode})`)
        break
      }
      case 'memory_recall': {
        recallHits.value = (e.data.hits as RecallHit[]) ?? []
        break
      }
      case 'message_end': {
        const content = String(e.data.content ?? '')
        const bubble = ensureAssistant()
        bubble.content = content
        bubble.streaming = false
        if (e.data.memory) memory.value = e.data.memory as MemorySnapshot
        break
      }
      case 'error': {
        messages.value.push({
          key: uid('e'),
          role: 'notice',
          content: `出错了:${e.data.message ?? '未知错误'}${e.data.retryable ? '(可直接重发)' : ''}`,
          error: true,
        })
        break
      }
      default:
        break
    }
  }

  try {
    await streamChat({ message: text, conversationId, userId }, handle)
  } catch (e) {
    messages.value.push({
      key: uid('e'),
      role: 'notice',
      content: `连接中断:${(e as Error).message}`,
      error: true,
    })
  } finally {
    sending.value = false
    messages.value.forEach((m) => {
      m.streaming = false
    })
    await refreshList()
    await refreshStats()
    try {
      const d = await api.detail(conversationId)
      memory.value = snapshotFromDetail(d)
      archivedCount.value = d.archivedCount
    } catch {
      /* 忽略 */
    }
    await scrollToBottom()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

onMounted(async () => {
  try {
    info.value = await api.info()
  } catch {
    /* 后端没起来时前端仍可渲染 */
  }
  await refreshList()
  await refreshStats()
})
</script>

<template>
  <div class="shell">
    <!-- 左侧:会话列表 -->
    <aside class="sidebar card">
      <div class="brand">
        <div class="logo">M</div>
        <div>
          <h1>长记忆 Agent</h1>
          <p>Redis · 滚动摘要 · 归档召回</p>
        </div>
      </div>
      <button class="new-btn" @click="newConversation">+ 新建对话</button>
      <ConversationList
        :conversations="conversations"
        :current-id="currentId"
        @select="openConversation"
        @remove="removeConversation"
      />
      <div class="sidebar-foot">
        <span>存储:{{ stats?.store ?? '—' }}</span>
        <span>会话数:{{ stats?.conversations ?? 0 }}</span>
        <span>摘要熔断:{{ stats?.summarizerBreakerOpen ? '已打开' : '正常' }}</span>
      </div>
    </aside>

    <!-- 中间:对话区 -->
    <main class="main card">
      <header class="topbar">
        <div>
          <h2>{{ currentTitle }}</h2>
          <div class="sub">
            {{ info?.provider ?? 'mock' }} · {{ info?.model ?? '—' }}
            <span v-if="info && !info.realModel"> · 离线演示</span>
            <span v-if="currentId"> · {{ currentId.slice(0, 8) }}</span>
          </div>
        </div>
        <div class="meter">
          <div class="meter-head">
            <span>记忆水位</span>
            <span>{{ memory?.tokensUsed ?? 0 }} / {{ memory?.budgetTokens ?? 6000 }}</span>
          </div>
          <div class="meter-bar">
            <div class="meter-fill" :class="meterLevel" :style="{ width: percent + '%' }"></div>
          </div>
        </div>
      </header>

      <section ref="listEl" class="messages">
        <div v-if="!messages.length" class="empty">
          <h3>聊得久一点,看看它是怎么记住的</h3>
          <p>每一轮消息都会落库;上下文超过预算的 72% 时,最早的整轮对话会被折叠成摘要,原文进归档。</p>
          <ul>
            <li>第 1 轮:<code>订单号 A20261123001 退款 899.00 元,承诺 2026-11-25 前到账,进度呢?</code></li>
            <li>接着连发 6~10 轮,右侧面板会看到压缩事件与水位回落</li>
            <li>再问:<code>刚才那个订单的金额是多少?</code> —— 摘要与关键事实会兜住</li>
            <li>问一句 <code>帮我建个工单催财务</code> —— 观察工具卡片与两步编排</li>
          </ul>
        </div>

        <MessageItem v-for="m in messages" :key="m.key" :msg="m" />

        <div v-if="toast" class="notice">{{ toast }}</div>
      </section>

      <footer class="composer">
        <div class="composer-box">
          <textarea
            v-model="input"
            rows="1"
            placeholder="说点什么…(Enter 发送 / Shift+Enter 换行)"
            :disabled="sending"
            @keydown="onKeydown"
          ></textarea>
          <button class="send-btn" :disabled="sending || !input.trim()" @click="send">
            {{ sending ? '生成中' : '发送' }}
          </button>
        </div>
        <div class="composer-hint">
          <span>同一会话串行处理:上一条没结束,新消息会收到 429。</span>
          <span>{{ messages.length }} 条消息</span>
        </div>
      </footer>
    </main>

    <!-- 右侧:记忆面板 -->
    <aside class="memory card">
      <div class="memory-head">
        <h3>记忆面板</h3>
        <span class="pill" :class="{ warn: percent > 70 }">{{ percent }}%</span>
      </div>
      <MemoryPanel
        :memory="memory"
        :recall-hits="recallHits"
        :archived-count="archivedCount"
        :compacting="compacting"
        @compact="doCompact"
        @refresh="refreshMemory"
      />
    </aside>
  </div>
</template>
