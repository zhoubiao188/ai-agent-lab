<script setup lang="ts">
import type { ToolRun } from '../composables/useAgent'
import { renderMarkdown } from '../utils/markdown'

defineProps<{ tools: ToolRun[] }>()

const ICON: Record<ToolRun['status'], string> = {
  running: '⏳',
  ok: '✅',
  error: '⚠️',
}

function prettyArgs(args?: string): string {
  if (!args) return ''
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}

function md(text: string) {
  return renderMarkdown(text)
}
</script>

<template>
  <div class="tool-panel">
    <div v-for="t in tools" :key="t.id" class="tool-card" :class="t.status">
      <div class="tool-head">
        <span class="tool-icon">{{ ICON[t.status] }}</span>
        <code class="tool-name">{{ t.name }}</code>
        <span v-if="t.elapsedMs !== undefined" class="tool-time">{{ t.elapsedMs }} ms</span>
      </div>
      <details v-if="t.args || t.content">
        <summary>参数与返回值</summary>
        <pre v-if="t.args" class="tool-args">{{ prettyArgs(t.args) }}</pre>
        <div v-if="t.content" class="tool-result" v-html="md(t.content)"></div>
      </details>
    </div>
  </div>
</template>
