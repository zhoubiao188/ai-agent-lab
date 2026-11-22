import { useCallback, useEffect, useRef, useState } from 'react'
import { streamChat } from '../lib/sse'

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

const uid = () => Math.random().toString(36).slice(2, 10)

/**
 * 用函数式更新维护"最后一条 assistant 消息"。
 * 关键点:所有事件都是对同一个消息对象的增量 patch,必须走 setState(prev => ...),
 * 否则并发事件会互相覆盖(经典 stale closure 坑)。
 */
export function useAgentStream() {
  const [messages, setMessages] = useState<ChatMsg[]>([])
  const [busy, setBusy] = useState(false)
  const [info, setInfo] = useState<AgentInfo | null>(null)
  const [infoError, setInfoError] = useState('')
  const conversationRef = useRef<string | undefined>(undefined)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    fetch('/api/agent/info')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((d: AgentInfo) => {
        setInfo(d)
        setInfoError('')
      })
      .catch((e: Error) => setInfoError(e.message))
  }, [])

  const patchLast = useCallback((patch: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      if (!prev.length) return prev
      const next = prev.slice()
      next[next.length - 1] = patch(next[next.length - 1])
      return next
    })
  }, [])

  const send = useCallback(
    async (raw: string) => {
      const text = raw.trim()
      if (!text) return
      setBusy(true)

      const assistantId = uid()
      setMessages((prev) => [
        ...prev,
        { id: uid(), role: 'user', text, tools: [], streaming: false },
        { id: assistantId, role: 'assistant', text: '', tools: [], streaming: true },
      ])

      const controller = new AbortController()
      abortRef.current = controller

      try {
        await streamChat({
          message: text,
          conversationId: conversationRef.current,
          signal: controller.signal,
          onEvent: ({ event, data }) => {
            if (event === 'message_start') {
              if (data?.conversationId) conversationRef.current = data.conversationId
              return
            }
            if (event === 'delta') {
              patchLast((m) => ({ ...m, text: m.text + (data?.text ?? '') }))
              return
            }
            if (event === 'delta_reset') {
              patchLast((m) => ({ ...m, text: '' }))
              return
            }
            if (event === 'tool_start') {
              patchLast((m) => ({
                ...m,
                tools: [...m.tools, { id: data?.id || `idx-${data?.index}`, name: data?.name, status: 'running' as const }],
              }))
              return
            }
            if (event === 'tool_result') {
              patchLast((m) => ({
                ...m,
                tools: m.tools.map((t) =>
                  t.id === data?.id || (!m.tools.some((x) => x.id === data?.id) && t.name === data?.name)
                    ? {
                        ...t,
                        args: data?.arguments,
                        success: Boolean(data?.success),
                        content: data?.content,
                        elapsedMs: data?.elapsedMs,
                        status: data?.success ? ('ok' as const) : ('error' as const),
                      }
                    : t,
                ),
              }))
              return
            }
            if (event === 'message_end') {
              patchLast((m) => ({
                ...m,
                text: data?.content || m.text,
                steps: data?.steps,
                streaming: false,
              }))
              return
            }
            if (event === 'done') {
              patchLast((m) => ({
                ...m,
                steps: data?.steps ?? m.steps,
                tokens: (data?.promptTokens ?? 0) + (data?.completionTokens ?? 0),
                elapsedMs: data?.elapsedMs,
              }))
              return
            }
            if (event === 'error') {
              patchLast((m) => ({ ...m, error: data?.message ?? '服务端错误', streaming: false }))
            }
          },
        })
      } catch (e: any) {
        if (e?.name !== 'AbortError') {
          patchLast((m) => ({ ...m, error: e?.message ?? '请求失败' }))
        }
      } finally {
        patchLast((m) => ({ ...m, streaming: false }))
        setBusy(false)
        abortRef.current = null
      }
    },
    [patchLast],
  )

  const abort = useCallback(() => abortRef.current?.abort(), [])
  const reset = useCallback(() => {
    setMessages([])
    conversationRef.current = undefined
  }, [])

  return { messages, busy, info, infoError, send, abort, reset }
}
