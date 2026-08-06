import { useCallback, useEffect, useRef, useState } from 'react'
import { authFetch } from '../api'

/**
 * SSE 流式传输 Hook（UX-006 拆分，design/17 §chat/hooks）
 *
 * 职责边界：POST 消息接口并消费 SSE 事件流（token/emotion/risk），
 * 不关心会话状态与 TTS——上层 useChatSession 编排业务。
 *
 * 安全兜底：
 * - 30s 无数据超时 → abort（防止永远卡在 streaming 态）
 * - 流异常且无任何回复 → 抛错（由上层渲染错误气泡）
 * - 卸载时中止挂起流（防泄漏）
 */
export interface SseStreamHandlers {
  onToken: (content: string) => void
  onEmotion: (label: string) => void
  onRisk: (riskLevel: number, content: string) => void
}

export interface SseStreamResult {
  fullResponse: string
}

/** SSE 解析所需的 reader 最小接口（测试可注入 mock） */
export interface SseStreamReader {
  read(): Promise<{ done: boolean; value?: Uint8Array }>
  cancel(): Promise<void>
}

/**
 * SSE 事件流消费（ARCH-005 F-1 唯一解析单点）
 *
 * 纯函数：传入 reader 与事件回调，返回累积的 token 完整文本。
 * 协议细节（data: 前缀 / 坏 JSON 静默 / 跨 chunk 半行缓冲 / token/emotion/risk 分发）
 * 只在这里实现一次——useSseStream.streamMessage 与 useSilenceNudge 共用。
 *
 * @param onChunk 每收到一段数据回调一次（供调用方维护超时计时等）
 */
export async function consumeSseStream(
  reader: SseStreamReader,
  handlers: SseStreamHandlers,
  onChunk?: () => void,
): Promise<string> {
  const decoder = new TextDecoder()
  let buffer = ''
  let fullResponse = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    onChunk?.()

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      try {
        const event = JSON.parse(line.slice(5))
        if (event.type === 'token') {
          fullResponse += event.content
          handlers.onToken(event.content)
        } else if (event.type === 'emotion') {
          handlers.onEmotion(event.content)
        } else if (event.type === 'risk') {
          handlers.onRisk(event.metadata?.riskLevel ?? 1, event.content)
        }
      } catch {
        /* 坏 JSON 行静默忽略 */
      }
    }
  }
  return fullResponse
}

const STREAM_IDLE_TIMEOUT_MS = 30000

export function useSseStream() {
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  const stopStream = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  const streamMessage = useCallback(
    async (
      url: string,
      body: Record<string, unknown>,
      handlers: SseStreamHandlers,
    ): Promise<SseStreamResult> => {
      setStreaming(true)
      const controller = new AbortController()
      abortRef.current = controller
      let fullResponse = ''
      let timeoutChecker: ReturnType<typeof setInterval> | null = null
      try {
        const res = await authFetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal: controller.signal,
        })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)

        const reader = res.body.getReader()
        let lastDataTime = Date.now()
        
        // SSE 流超时保护：30s 无数据 → 自动 abort（防止永远卡在 streaming 态）
        timeoutChecker = setInterval(() => {
          if (Date.now() - lastDataTime > STREAM_IDLE_TIMEOUT_MS) {
            if (timeoutChecker) clearInterval(timeoutChecker)
            controller.abort()
          }
        }, 5000)
        
        // 解析统一走 consumeSseStream（ARCH-005 F-1 单点）
        fullResponse = await consumeSseStream(
          reader,
          handlers,
          () => { lastDataTime = Date.now() },
        )
      } finally {
        if (timeoutChecker) clearInterval(timeoutChecker)
        abortRef.current = null
        setStreaming(false)
      }
      return { fullResponse }
    },
    [],
  )

  // 卸载时中止挂起的流
  useEffect(() => () => stopStream(), [stopStream])

  return { streaming, streamMessage, stopStream }
}
