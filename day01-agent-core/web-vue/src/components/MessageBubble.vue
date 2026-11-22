<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMsg } from '../composables/useAgent'
import { renderMarkdown } from '../utils/markdown'
import ToolPanel from './ToolPanel.vue'

const props = defineProps<{ msg: ChatMsg }>()

const html = computed(() => renderMarkdown(props.msg.text))
const isAssistant = computed(() => props.msg.role === 'assistant')
</script>

<template>
  <div class="msg" :class="msg.role">
    <div class="avatar">{{ isAssistant ? 'AI' : 'ME' }}</div>
    <div class="body">
      <ToolPanel v-if="msg.tools.length" :tools="msg.tools" />

      <div v-if="msg.text" class="bubble" v-html="html"></div>
      <div v-else-if="msg.streaming" class="bubble typing">
        <span></span><span></span><span></span>
      </div>

      <div v-if="msg.error" class="error-bar">⚠️ {{ msg.error }}</div>

      <div v-if="isAssistant && !msg.streaming && msg.steps" class="meta">
        共 {{ msg.steps }} 轮迭代
        <template v-if="msg.tokens"> · 约 {{ msg.tokens }} tokens</template>
        <template v-if="msg.elapsedMs !== undefined"> · {{ (msg.elapsedMs / 1000).toFixed(1) }}s</template>
      </div>
    </div>
  </div>
</template>
