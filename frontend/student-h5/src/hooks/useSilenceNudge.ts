import { useRef, useEffect, useCallback } from 'react'
import { getToken } from '../api'

/** 触发冷场检测的最小沉默秒数（design/28 §2.3） */
const SILENCE_TRIGGER_SECONDS = 25
/** 连续暖场次数上限（孩子一说话即清零） */
const MAX_CONSECUTIVE_NUDGES = 2
/** 两次暖场最小间隔（毫秒，与后端护栏一致） */
const NUDGE_MIN_INTERVAL_MS = 20000
/** 检测轮询间隔（毫秒） */
const CHECK_INTERVAL_MS = 5000

/**
 * 冷场检测 Hook（design/28 §2.3 触发与决策流程）
 *
 * 全部满足才上报 nudge：
 * - 会话已创建且至少一轮互动（孩子至少说过一次话）
 * - 孩子 ≥25s 未发送消息
 * - AI 未在说话/流式/录音（idle=true 由调用方保证）
 * - 距上次暖场 ≥20s 且本轮连续暖场 <2 次
 *
 * 后端决策模型计算"留白/轻陪伴/引导破冰"：
 * - 留白 → 返回空流，前端不做任何事（把安静还给孩子）
 * - 暖场 → SSE token 流，前端追加 AI 消息 + TTS 朗读
 *
 * @param {object} opts
 * @param {string} opts.sessionId  会话 ID
 * @param {boolean} opts.idle      AI 空闲（!streaming && !recording && !analyzing && !tts.playing && !tts.muted）
 * @param {(text: string) => void} opts.onNudge  暖场回复回调（完整文本，追加消息 + TTS）
 * @returns {{ recordInteraction: () => void, resetSilenceBase: () => void }}
 */
export function useSilenceNudge({ sessionId, idle, onNudge }) {
  /** 沉默起算时间戳（孩子最后说话 / AI 最后说完） */
  const silenceBaseRef = useRef(Date.now())
  /** 连续暖场计数（孩子说话清零） */
  const nudgeCountRef = useRef(0)
  /** 上次暖场时间戳 */
  const lastNudgeAtRef = useRef(0)
  /** nudge 请求进行中（防重入） */
  const inFlightRef = useRef(false)
  /** 是否已至少一轮互动（避免开场即暖场打扰） */
  const interactedRef = useRef(false)
  /** 最新 onNudge（避免闭包过期） */
  const onNudgeRef = useRef(onNudge)
  useEffect(() => { onNudgeRef.current = onNudge })

  /** 孩子说话：重置沉默基准 + 清零连续暖场计数 */
  const recordInteraction = useCallback(() => {
    interactedRef.current = true
    nudgeCountRef.current = 0
    silenceBaseRef.current = Date.now()
  }, [])

  /** AI 活动结束（流式/朗读完毕）：从此刻起算沉默 */
  const resetSilenceBase = useCallback(() => {
    silenceBaseRef.current = Date.now()
  }, [])

  useEffect(() => {
    // AI 忙碌（流式/录音/识别/朗读/静音）时暂停检测
    if (!idle) return

    const timer = setInterval(async () => {
      if (!interactedRef.current || inFlightRef.current) return
      const silenceSec = Math.floor((Date.now() - silenceBaseRef.current) / 1000)
      if (silenceSec < SILENCE_TRIGGER_SECONDS) return
      if (nudgeCountRef.current >= MAX_CONSECUTIVE_NUDGES) return
      if (Date.now() - lastNudgeAtRef.current < NUDGE_MIN_INTERVAL_MS) return

      inFlightRef.current = true
      try {
        const res = await fetch(`/api/v1/chat/sessions/${sessionId}/nudge`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
          },
          body: JSON.stringify({ silenceSeconds: silenceSec }),
        })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)

        // 解析 SSE（与 sendMessage 相同协议）：累积 token
        let fullText = ''
        const reader = res.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''
          for (const line of lines) {
            if (!line.startsWith('data:')) continue
            try {
              const event = JSON.parse(line.slice(5))
              if (event.type === 'token' && event.content) {
                fullText += event.content
              }
            } catch { /* ignore parse errors */ }
          }
        }

        if (fullText) {
          // 暖场成功：计数 + 回调（追加消息 + TTS 朗读）
          nudgeCountRef.current += 1
          lastNudgeAtRef.current = Date.now()
          onNudgeRef.current(fullText)
        }
        // 空流 = 后端决策"留白"：把安静还给孩子，不做任何事
      } catch (e) {
        // nudge 失败静默（不打扰孩子，后端也有护栏兜底）
        console.warn('冷场检测请求失败（静默忽略）:', e?.message)
      } finally {
        inFlightRef.current = false
        // 无论结果如何，从此刻重新起算沉默（避免空流后 5s 内频繁触发）
        silenceBaseRef.current = Date.now()
      }
    }, CHECK_INTERVAL_MS)

    return () => clearInterval(timer)
  }, [idle, sessionId])

  return { recordInteraction, resetSilenceBase }
}
