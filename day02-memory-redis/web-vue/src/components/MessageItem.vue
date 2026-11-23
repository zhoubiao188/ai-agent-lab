<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import type { UiMessage } from '../types'

const props = defineProps<{ msg: UiMessage }>()

const html = computed(() => renderMarkdown(props.msg.content || ''))
const isTool = computed(() => props.msg.role === 'tool')
</script>

<template>
  <!-- 工具调用卡片:和普通消息分开渲染,避免把工具原始输出当成"AI 的回答" -->
  <div v-if="isTool" class="tool-card" :class="{ failed: msg.success === false }">
    <div class="tool-head">
      <span>⚙</span>
      <span>{{ msg.toolName || 'tool' }}</span>
      <span v-if="msg.elapsedMs" style="color: #9aa1ad; font-weight: 400">
        {{ msg.elapsedMs }}ms
      </span>
      <span v-if="msg.streaming" style="color: #9aa1ad; font-weight: 400">执行中…</span>
    </div>
    <div v-if="msg.toolArgs" class="tool-body" style="color: #94a3b8">参数:{{ msg.toolArgs }}</div>
    <div v-if="msg.content" class="tool-body">{{ msg.content }}</div>
  </div>

  <div v-else-if="msg.role === 'notice'" class="notice" :class="{ error: msg.error }">
    {{ msg.content }}
  </div>

  <div v-else class="msg" :class="msg.role">
    <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
    <div class="bubble">
      <span v-if="msg.role === 'assistant'" v-html="html"></span>
      <span v-else>{{ msg.content }}</span>
      <span v-if="msg.streaming" class="cursor"></span>
    </div>
  </div>
</template>
