/** 前端展示用的消息模型(与后端 Msg 解耦:多了 streaming / 失败态等 UI 字段) */
export interface UiMessage {
  key: string
  role: 'user' | 'assistant' | 'tool' | 'notice'
  content: string
  toolName?: string
  toolArgs?: string
  success?: boolean
  streaming?: boolean
  error?: boolean
  elapsedMs?: number
}

let seq = 0

export function uid(prefix = 'k'): string {
  seq += 1
  return `${prefix}-${Date.now().toString(36)}-${seq}`
}
