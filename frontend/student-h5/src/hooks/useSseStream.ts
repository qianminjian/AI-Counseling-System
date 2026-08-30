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
  /** AUTH-030：每日使用时长已达上限（前端展示休息引导页并禁用输入；useSilenceNudge 等旧调用方不传，可选） */
  onUsageLimit?: (guidance: string) => void
  /** AUTH-030：距上限不足预警（每日最多一次的顶部提示） */
  onUsageWarning?: (text: string) => void
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
        } else if (event.type === 'usage_limit') {
          handlers.onUsageLimit?.(event.content)
        } else if (event.type === 'usage_warning') {
          handlers.onUsageWarning?.(event.content)
        }
      } catch {
        /* 坏 JSON 行静默忽略 */
      }
    }
  }
  return fullResponse
}

// 99-4（2026-08-14）：语义拆分——连接建立超时 vs 流空闲超时（原共用一个 IDLE 命名误导）
const CONNECT_TIMEOUT_MS = 30000 // 请求发出 → 响应头到达的窗口（LLM 首包最坏场景）
const STREAM_IDLE_TIMEOUT_MS = 30000 // 最后一条数据 → 无数据自动 abort 的窗口

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
      let requestTimeout: ReturnType<typeof setTimeout> | null = setTimeout(() => controller.abort(), CONNECT_TIMEOUT_MS)
      try {
        const res = await authFetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal: controller.signal,
        })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        if (requestTimeout) {
          clearTimeout(requestTimeout)
          requestTimeout = null
        }

        // FE-003：res.ok 已确认响应成功，body 必然存在（类型层面 res.body 可空，运行时不变）
        const reader = res.body!.getReader()
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
        if (requestTimeout) clearTimeout(requestTimeout)
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
