<script setup lang="ts">
import type { ConversationSummary } from '../api/agent'

defineProps<{
  conversations: ConversationSummary[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'remove', id: string): void
}>()

function pct(c: ConversationSummary): number {
  return c.budgetTokens ? Math.min(100, Math.round((c.tokensUsed / c.budgetTokens) * 100)) : 0
}

function timeText(ts: number): string {
  if (!ts) return ''
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Date(ts).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<template>
  <div class="conv-list">
    <div
      v-for="c in conversations"
      :key="c.id"
      class="conv-item"
      :class="{ active: c.id === currentId }"
      @click="emit('select', c.id)"
    >
      <div class="conv-title">{{ c.title }}</div>
      <div class="conv-meta">
        <span>{{ timeText(c.updatedAt) }}</span>
        <span class="pill" :class="{ warn: pct(c) > 70 }">{{ pct(c) }}%</span>
        <span v-if="c.compactRuns > 0" class="pill">压缩 {{ c.compactRuns }}</span>
      </div>
      <button class="del-btn" title="删除会话" @click.stop="emit('remove', c.id)">×</button>
    </div>

    <div v-if="!conversations.length" style="padding: 10px; font-size: 12px; color: #9aa1ad">
      还没有历史会话。发一条消息就会自动落库。
    </div>
  </div>
</template>
