import { computed, reactive, ref } from 'vue'
import { streamChat, type AgentEvent } from '../api/agent'

export interface ToolRun {
  id: string
  name: string
  args?: string
  success?: boolean
  content?: string
  elapsedMs?: number
  status: 'running' | 'ok' | 'error'
}

export interface ChatMsg {
  id: string
  role: 'user' | 'assistant'
  text: string
  tools: ToolRun[]
  streaming: boolean
  steps?: number
  tokens?: number
  elapsedMs?: number
  error?: string
}

export interface AgentInfo {
  provider: string
  realModel: boolean
  model: string
  maxSteps: number
  tools: string[]
}

export function useAgent() {
  const messages = ref<ChatMsg[]>([])
  const busy = ref(false)
  const info = ref<AgentInfo | null>(null)
  const infoError = ref('')
  const conversationId = ref<string | undefined>(undefined)
  let abortController: AbortController | null = null

  const canSend = computed(() => !busy.value)

  async function loadInfo() {
    try {
      const resp = await fetch('/api/agent/info')
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      info.value = await resp.json()
      infoError.value = ''
    } catch (e: any) {
      infoError.value = e?.message ?? '无法连接后端'
    }
  }

  async function send(text: string) {
    const content = text.trim()
    if (!content || busy.value) return

    messages.value.push({ id: uid(), role: 'user', text: content, tools: [], streaming: false })
    const assistant = reactive<ChatMsg>({
      id: uid(),
      role: 'assistant',
      text: '',
      tools: [],
      streaming: true,
    })
    messages.value.push(assistant)

    busy.value = true
    abortController = new AbortController()
    let pendingReset = false

    try {
      await streamChat({
        message: content,
        conversationId: conversationId.value,
        signal: abortController.signal,
        onEvent: (e: AgentEvent) => handle(e, assistant, () => (pendingReset = true)),
      })
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        assistant.error = e?.message ?? '请求失败'
      }
    } finally {
      assistant.streaming = false
      busy.value = false
      abortController = null
      void pendingReset
    }
  }

  function handle(e: AgentEvent, target: ChatMsg, markReset: () => void) {
    const d = e.data ?? {}
    switch (e.event) {
      case 'message_start':
        if (d.conversationId) conversationId.value = d.conversationId
        break
      case 'delta':
        target.text += d.text ?? ''
        break
      case 'delta_reset':
        // 模型在发起工具调用前吐的过渡语要撤回,否则会出现"我先查一下…"+最终答案的重复
        markReset()
        target.text = ''
        break
      case 'tool_start':
        target.tools.push({
          id: d.id || `idx-${d.index}`,
          name: d.name,
          status: 'running',
        })
        break
      case 'tool_result': {
        const t = target.tools.find((x) => x.id === d.id) ?? target.tools.find((x) => x.name === d.name)
        if (t) {
          t.args = d.arguments
          t.success = d.success
          t.content = d.content
          t.elapsedMs = d.elapsedMs
          t.status = d.success ? 'ok' : 'error'
        }
        break
      }
      case 'message_end':
        if (typeof d.content === 'string' && d.content) target.text = d.content
        target.steps = d.steps
        break
      case 'done':
        target.steps = d.steps ?? target.steps
        target.tokens = (d.promptTokens ?? 0) + (d.completionTokens ?? 0)
        target.elapsedMs = d.elapsedMs
        break
      case 'error':
        target.error = d.message ?? '服务端错误'
        break
    }
  }

  function abort() {
    abortController?.abort()
  }

  function reset() {
    messages.value = []
    conversationId.value = undefined
  }

  return { messages, busy, info, infoError, canSend, loadInfo, send, abort, reset }
}

function uid(): string {
  return Math.random().toString(36).slice(2, 10)
}
