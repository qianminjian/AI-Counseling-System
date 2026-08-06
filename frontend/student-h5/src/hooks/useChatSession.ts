import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { emotionBus } from '../utils/emotionBus'
import { useSseStream } from './useSseStream'
import type { ChatMessage } from '../components/MessageBubble'

/** 语音情绪对象（/api/v1/voice/analyze 返回，Java 端 VoiceController 组装） */
export interface VoiceEmotion {
  label: string
  labelEn: string
  confidence: number
  scores: number[]
}

/** TTS 播放器最小接口（ChatRoom 传入真实 useTtsPlayer，测试传入 mock） */
export interface ChatTtsLike {
  muted: boolean
  stop(): void
  unlock(): void
  startStreaming(): void
  feedToken(content: string): void
  endStreaming(): void
}

/** 波波表情状态机最小接口（ChatRoom 传入 useBoboExpression） */
export interface BoboExpressionLike {
  dispatch(evt: { type: string; riskLevel?: number }): void
}

export interface ChatSessionOptions {
  sessionId: string
  greeting: string
  emotionTag: string
  tts: ChatTtsLike
  /** 同步前端设置状态，让 AI 知道自己的能力边界 */
  wakeEnabled: boolean
  bobo: BoboExpressionLike
  /** 冷场引导：孩子一说话即重置沉默计时（design/28 §2.3） */
  onInteraction?: () => void
  /** 发送前预处理（打断朗读标记/释放麦克风等安卓路由保护，design/27 §5.1） */
  onBeforeSend?: () => void
  /** 会话关闭完成回调（由 ChatRoom 驱动 onEnd 导航） */
  onClosed?: () => void
}

/**
 * 聊天会话状态 Hook（UX-006 拆分，design/17 §chat/hooks）
 *
 * 职责边界：消息列表/输入/发送编排（TTS 联动、情绪总线、波波状态机、错误降级、关闭会话），
 * SSE 传输细节委托 useSseStream。
 */
export function useChatSession(opts: ChatSessionOptions) {
  const { sessionId, greeting, emotionTag, tts, wakeEnabled, bobo, onInteraction, onBeforeSend, onClosed } = opts
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'assistant', content: greeting, emotion: emotionTag },
  ])
  const [input, setInput] = useState('')
  const { streaming, streamMessage } = useSseStream()

  // 指向最新的 sendMessage（供录音完成回调等定义在前的调用方使用，避免 TDZ 与闭包过期）
  const sendMessageRef = useRef<(autoText?: string, autoEmotion?: VoiceEmotion | null) => Promise<boolean>>(null)

  /** 语音识别去重：检测文本后半段是否与前半段重复（Android 语音识别/Whisper 偶发） */
  const deduplicateText = (raw: string): string => {
    const t = raw.trim()
    if (t.length < 6) return t
    // 尝试从中间偏后位置检测：后半段 === 前半段（允许标点差异）
    const len = t.length
    for (let mid = Math.floor(len * 0.4); mid <= Math.ceil(len * 0.6); mid++) {
      const first = t.slice(0, mid).replace(/[，。！？、\s]/g, '')
      const second = t.slice(mid).replace(/[，。！？、\s]/g, '')
      if (first && second && first === second) return t.slice(0, mid)
    }
    return t
  }

  const sendMessage = async (autoText?: string, autoEmotion?: VoiceEmotion | null) => {
    const text = deduplicateText((autoText ?? input).trim())
    if (!text || streaming) return false

    // 先停止当前播放，再在用户手势中解锁音频（避免 unlock 被 stop 打断）
    tts.stop()
    tts.unlock()
    // 发送前预处理：打断朗读标记 + 释放麦克风（安卓把活跃麦克风视为"通话"，会把 TTS 路由到听筒；
    // 在发送时（AI 生成前）就释放，给系统留足切回扬声器的时间，确保整段回复走扩音）
    onBeforeSend?.()

    // 语音自动发送时用传入的 emotion（服务端情绪存入 state）；手动打字时无情绪标注
    const emotion = autoEmotion

    const body: Record<string, unknown> = { content: text }
    if (emotion) {
      body.voiceEmotion = emotion.labelEn
      body.voiceEmotionConfidence = emotion.confidence
      body.inputMode = 'voice'
    }
    // 同步前端设置状态，让 AI 知道自己的能力边界（TTS是否开启/唤醒是否开启）
    body.ttsMuted = tts.muted
    body.wakeEnabled = wakeEnabled

    const msgEmotion = emotion?.labelEn
    setInput('')
    // 冷场引导：孩子一说话即重置沉默计时 + 清零连续暖场计数
    onInteraction?.()
    setMessages((prev) => [...prev, { role: 'user', content: text, emotion: msgEmotion }])
    // AI 回复挂上孩子情绪（语音情绪优先、会话情绪兜底），驱动情感化排印
    setMessages((prev) => [...prev, { role: 'assistant', content: '', emotion: msgEmotion || emotionTag }])

    let fullResponse = ''
    let replyEmotionReceived = false

    // AI 思考中：波波切换 think 表情（design/37 §4.1）
    bobo.dispatch({ type: 'thinking' })

    // 流式 TTS：首句完成即开始合成播放，不等全文接收完
    if (!tts.muted) tts.startStreaming()

    try {
      const result = await streamMessage(
        `/api/v1/chat/sessions/${sessionId}/messages`,
        body,
        {
          onToken: (content) => {
            fullResponse += content
            // 流式 TTS：每个 token 喂入，首句完成即开始合成播放
            if (!tts.muted) tts.feedToken(content)
            setMessages((prev) => {
              const updated = [...prev]
              const last = updated[updated.length - 1]
              updated[updated.length - 1] = { ...last, content: last.content + content }
              return updated
            })
          },
          onEmotion: (label) => {
            // AI 回复情绪标签（design/37 §三.1 三方同源）：统一经 emotionBus 分发给
            // 表情状态机/TTS/主题层，禁止各消费方另取信号源
            replyEmotionReceived = true
            emotionBus.publish(label)
          },
          onRisk: (riskLevel, content) => {
            // 风险事件是系统/心理老师的内部处理指令（如"允许继续 CBT 微干预，趋势观察"），
            // 不能展示给学生：孩子看不懂临床术语，且会意识到"被监控"而破坏辅导信任。
            // 风险数据由后端另行落库并推送教师后台；红色风险后端会单独下发 孩子能懂的安抚语。
            // 这里仅记录日志便于调试，不渲染到聊天界面。
            // S0/S1 风险锁定波波 hug 安抚姿态（design/37 §4.1 安全红线，riskLevel≥2 锁定）
            bobo.dispatch({ type: 'risk', riskLevel })
          },
        },
      )
      fullResponse = result.fullResponse
    } catch (e) {
      // SSE 流读取异常。常见于流结束时 chunked 终止块缺失（服务端异步收尾不干净），
      // 此时 AI 回复其实已完整接收——保留已收到的内容，仅在完全没收到时才提示错误。
      if (fullResponse) {
        console.warn('SSE 流终止异常但回复已接收，忽略:', (e as Error)?.message)
      } else {
        console.error('发送失败', e)
        setMessages((prev) => {
          const updated = [...prev]
          updated[updated.length - 1] = { role: 'assistant', content: '网络出了点问题，请再试一次哦 🙏', emotion: null }
          return updated
        })
      }
    } finally {
      // 流式 TTS 结束：冲刷剩余缓冲 + 等待播放完毕；无内容时重置状态
      if (!tts.muted) {
        if (fullResponse) {
          tts.endStreaming()
        } else {
          tts.stop()
        }
      }
      // 流结束：本轮无情绪标签则波波回落 idle（有则保持情绪表情）
      if (!replyEmotionReceived) bobo.dispatch({ type: 'idle' })
    }
    return true
  }

  // 每次渲染后让 sendMessageRef 指向最新的 sendMessage（供定义在前的录音回调调用）
  useEffect(() => {
    sendMessageRef.current = sendMessage
  })

  const closeSession = async (rating?: number | null, comment?: string) => {
    try {
      const body = rating ? { rating, comment } : undefined
      await api(`/sessions/${sessionId}/close`, {
        method: 'POST',
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch {
      // ARCH-010 D5（OVD-4）：旧关闭接口已下线，主接口失败静默（不影响 UI 关闭流程）
    }
    onClosed?.()
  }

  return { messages, setMessages, input, setInput, streaming, sendMessage, sendMessageRef, closeSession, deduplicateText }
}
