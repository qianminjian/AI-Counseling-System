/**
 * SpeechRecognition 共享装配层（FA-10）
 *
 * 此前同一浏览器能力在 useVoiceInputPipeline / useVoiceCallMode 各装配一遍，
 * Android 重复 final bug 只在一处修（另一处靠防抖+首轮过滤），修一处漏一处。
 * 此处收敛：语言/连续/interim/启动停止/错误捕获单点，两调用方只接结果回调。
 *
 * 聚合策略（参数化，各自保持原行为）：
 * - 'dedupe'（默认，按住说话链）：final 去重拼接 + interim 实时展示
 *   （Android Chrome 中文 continuous 模式语句结束后 results 出现重复 final 条目，
 *   只拼 final + 跳过连续相同文本；interim 仅用于实时展示）
 * - 'concat'（唤醒通话链）：全部 result 全量拼接（防抖判断依赖完整文本）
 */
export type SpeechAggregateMode = 'dedupe' | 'concat'

export interface SpeechRecognitionOptions {
  lang?: string
  /** 结果聚合策略，默认 'dedupe' */
  aggregate?: SpeechAggregateMode
  /** 文本更新：sendText=发送用（final 去重后/全量拼接），displayText=实时展示（含 interim） */
  onText?: (sendText: string, displayText: string) => void
  /** 识别会话自然结束（silent stop 不触发） */
  onEnd?: () => void
  /** 识别错误（error code，如 no-speech） */
  onError?: (error: string) => void
}

export interface SpeechRecognitionHandle {
  /** 停止识别；silent=true 不触发 onEnd（主动停止场景：发送后/卸载前） */
  stop: (silent?: boolean) => void
}

/**
 * 装配并启动一次语音识别；浏览器不支持或启动失败返回 null。
 * 注意：连续模式下每个聆听回合用独立实例（iOS 稳健性），用完即 stop 丢弃。
 */
export function createSpeechRecognition(options: SpeechRecognitionOptions = {}): SpeechRecognitionHandle | null {
  const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
  if (!SR) return null
  try {
    const rec = new SR()
    rec.lang = options.lang ?? 'zh-CN'
    rec.continuous = true
    rec.interimResults = true
    rec.onresult = (e: any) => {
      const isDedupe = (options.aggregate ?? 'dedupe') === 'dedupe'
      let sendText = ''
      let displayText = ''
      if (isDedupe) {
        // 每轮全量重算（results 累积历史）：final 去重拼接 + interim 展示
        // display 按 results 出现顺序混合拼接（interim 在前 final 在后等，与原实现一致）
        let finalText = ''
        let interimText = ''
        let display = ''
        let prevFinal = ''
        for (let i = 0; i < e.results.length; i++) {
          const text = e.results[i][0].transcript
          if (e.results[i].isFinal) {
            if (text !== prevFinal) { // 跳过连续相同 final（Android 重复 bug）
              finalText += text
              prevFinal = text
              display += text
            }
          } else {
            interimText += text
            display += text
          }
        }
        sendText = finalText || interimText
        displayText = display
      } else {
        // concat：全量拼接（含 interim + final），空文本不回调
        let full = ''
        for (let i = 0; i < e.results.length; i++) {
          full += e.results[i][0].transcript
        }
        const t = full.trim()
        if (!t) return
        sendText = t
        displayText = t
      }
      options.onText?.(sendText, displayText)
    }
    rec.onend = () => options.onEnd?.()
    rec.onerror = (e: any) => options.onError?.(e.error)
    rec.start()
    return {
      stop: (silent = false) => {
        if (silent) rec.onend = null // 主动停止不触发重启逻辑
        try { rec.stop() } catch { /* ignore */ }
      },
    }
  } catch (err) {
    console.warn('浏览器语音识别启动失败', err)
    return null
  }
}
