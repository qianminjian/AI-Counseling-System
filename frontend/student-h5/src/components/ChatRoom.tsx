import { useState, useRef, useEffect, useCallback } from 'react'
import VoiceConsentDialog, { useVoiceConsent } from './VoiceConsentDialog'
import VoiceCallConsentDialog, { useVoiceCallConsent } from './VoiceCallConsentDialog'
import SatisfactionDialog from './SatisfactionDialog'
import SettingsPanel from './SettingsPanel'
import ConfirmDialog from './ConfirmDialog'
import BoBoPet from './BoBoPet'
import DraggableVoiceButton from './DraggableVoiceButton'
import MessageBubble, { EMOTION_EMOJI, type ChatMessage } from './MessageBubble'
import { useTheme } from '../theme/ThemeProvider'
import { useVoicePersona } from '../hooks/useVoicePersona'
import { useTtsPlayer } from '../hooks/useTtsPlayer'
import { useVoiceCallMode } from '../hooks/useVoiceCallMode'
import { preloadWakeModel } from '../hooks/useWakeWord'
import { useSilenceNudge } from '../hooks/useSilenceNudge'
import { useAudioRecorder } from '../hooks/useAudioRecorder'
import { authFetch, api, getUser } from '../api'

/** 语音唤醒开关持久化 key（design/28 §1.1） */
const WAKE_PREF_KEY = 'mindsafe_wake_enabled'

/** 会话信息（由 EmotionSelect 传入） */
export interface SessionInfo {
  sessionId: string
  greeting: string
  emotionTag: string
}

export default function ChatRoom({ session, onEnd, onSwitchUser }: { session: SessionInfo; onEnd: () => void; onSwitchUser?: () => void }) {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: session.greeting, emotion: session.emotionTag },
  ])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [voiceEmotion, setVoiceEmotion] = useState(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [speakingMsgIdx, setSpeakingMsgIdx] = useState(-1)
  const [voiceNotice, setVoiceNotice] = useState('')
  const [cancelArmed, setCancelArmed] = useState(false) // 按住说话：上滑进入取消态
  const [liveTranscript, setLiveTranscript] = useState('') // 录音中实时转写（浏览器识别，作反馈展示）
  const [confirmSwitch, setConfirmSwitch] = useState(false) // 切换同学确认弹窗
  const bottomRef = useRef(null)
  const browserTranscriptRef = useRef('')
  const speechRecRef = useRef(null)
  const greetingSpokenRef = useRef(false)
  // 指向最新的 sendMessage（handleRecordingComplete 定义在前、sendMessage 定义在后，用 ref 避免 TDZ 与闭包过期）
  const sendMessageRef = useRef(null)
  const pointerStartYRef = useRef(0) // 按下时 Y 坐标（检测上滑取消）
  const pointerDownTimeRef = useRef(0) // 按下时间戳（检测说话过短）

  // 主题 + 音色
  const { theme } = useTheme()
  const { personaId } = useVoicePersona()

  // 语音授权（合规）
  const { showDialog: showConsent, hasConsent, requestConsent, grantConsent, denyConsent } = useVoiceConsent()

  // 语音唤醒（design/28 §1.1）：单独授权 + 开关持久化
  const [wakeEnabled, setWakeEnabled] = useState(() => localStorage.getItem(WAKE_PREF_KEY) !== '0')
  const wakeConsent = useVoiceCallConsent()

  // TTS 播放器（语速根据性别微调：男生稍快、女生稍慢）
  const userGender = getUser()?.gender
  const tts = useTtsPlayer({
    persona: personaId,
    emotion: voiceEmotion?.labelEn || 'neutral',
    speed: userGender === 'male' ? 1.05 : userGender === 'female' ? 0.95 : 1.0,
  })

  // 进入聊天室自动朗读打招呼语
  // （此时仍处于"开始聊天"点击的用户激活窗口内，unlock 可成功预热音频元素）
  useEffect(() => {
    if (greetingSpokenRef.current) return // StrictMode 防重复
    greetingSpokenRef.current = true
    if (session.greeting && !tts.muted) {
      tts.unlock()
      tts.speak(session.greeting)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // TTS 引擎不可用提示（安卓 Pad 无 Google 语音引擎时显示友好提示，而非系统报错）
  useEffect(() => {
    if (tts.engine === 'none') {
      setVoiceNotice('当前浏览器不支持语音播放，可阅读文字内容 📖')
      setTimeout(() => setVoiceNotice(''), 6000)
    }
  }, [tts.engine])

  // 首次进入：唤醒默认开启但未授权 → 自动弹出授权说明（合规，design/28 §1.4）
  useEffect(() => {
    if (wakeEnabled && !wakeConsent.hasConsent()) {
      const t = setTimeout(() => wakeConsent.requestConsent(), 800)
      return () => clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 预加载唤醒模型：一进对话就开始下载，利用 TTS 播放时间窗口
  useEffect(() => {
    if (wakeEnabled && wakeConsent.hasConsent()) {
      preloadWakeModel()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 录音完成 → 上传分析 → 自动发送（audioBlob 为 null 时直接用浏览器转写） */
  const handleRecordingComplete = useCallback(async (audioBlob) => {
    // 无录音（麦克风不可用）→ 直接用浏览器识别结果
    if (!audioBlob) {
      const text = browserTranscriptRef.current
      if (text) {
        setVoiceNotice('已用浏览器识别（语音情绪分析暂不可用）')
        sendMessageRef.current?.(text, null).then((sent) => { if (!sent) setInput(text) })
      } else {
        setVoiceNotice('没有听清，请再说一次或打字告诉我 ✏️')
      }
      setTimeout(() => setVoiceNotice(''), 4000)
      return
    }

    const formData = new FormData()
    formData.append('file', audioBlob, 'recording.webm')

    try {
      // authFetch 统一处理 JWT 认证 + 401 自动刷新，避免 token 过期导致降级到不可靠的浏览器识别
      const res = await authFetch('/api/v1/voice/analyze', {
        method: 'POST',
        body: formData,
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const json = await res.json()
      if (json.success && json.data) {
        const { text, emotion } = json.data
        if (text) {
          // 自动发送：emotion 直接传给 sendMessage（修复此前情绪从未存入 state、预览不显示的问题）
          // 不 await，避免 analyzing 状态覆盖整个 AI 回复过程；极端被拦截时回填输入框防丢字
          sendMessageRef.current?.(text, emotion).then((sent) => {
            if (!sent) setInput(text)
          })
          return
        }
      }
      throw new Error('服务端未返回文字')
    } catch (err) {
      console.warn('语音分析服务不可用，尝试浏览器识别', err.message)
      // 降级：使用浏览器 Web Speech API 实时识别结果
      const text = browserTranscriptRef.current
      if (text) {
        setVoiceNotice('已用浏览器识别（语音情绪分析暂不可用）')
        sendMessageRef.current?.(text, null).then((sent) => { if (!sent) setInput(text) })
      } else {
        setVoiceNotice('语音识别暂不可用，请打字告诉我吧 ✏️')
      }
      setTimeout(() => setVoiceNotice(''), 4000)
    }
  }, [])

  const { recording, analyzing, supported, startRecording, stopRecording, cancelRecording, warmUp: warmUpMic, releaseStream } = useAudioRecorder(handleRecordingComplete)

  /* ===== 语音唤醒状态机（design/28 §1.1）：off / standby（待唤醒）/ active（会话窗）
     监听严格限定在本次对话内：仅 ChatRoom 挂载期间由 enabled 控制，卸载即释放麦克风 ===== */
  const voiceCall = useVoiceCallMode({
    enabled: wakeEnabled && wakeConsent.hasConsent(),
    tts,
    busy: streaming || tts.playing || recording || analyzing,
    onFinalTranscript: (text) => {
      // 唤醒后孩子说话 → 走与按住说话相同的自动发送流程
      // 失败时回填输入框防丢字（与 handleRecordingComplete 保持一致）
      sendMessageRef.current?.(text, null).then((sent) => {
        if (!sent) setInput(text)
      })
    },
  })

  /* ===== 冷场引导（design/28 §2.3）：孩子长时间沉默时，后端决策模型决定“留白还是暖场”
     唤醒模式开启时不做冷场检测——沉默由会话窗冷却关窗处理（design/28 三功能协同） ===== */
  const { recordInteraction, resetSilenceBase } = useSilenceNudge({
    sessionId: session.sessionId,
    // AI 忙碌（流式/录音/识别/朗读）或静音时不做冷场检测；唤醒模式（standby/active）时互斥
    idle: !streaming && !recording && !analyzing && !tts.playing && !tts.muted && voiceCall.mode === 'off',
    onNudge: (text) => {
      // 暖场回复：追加 AI 消息气泡 + TTS 朗读（复用现有体验，跟随所选音色）
      setMessages((prev) => [...prev, { role: 'assistant', content: text, emotion: session.emotionTag }])
      if (!tts.muted) tts.speak(text)
    },
  })

  // AI 活动结束（回复流/朗读完毕）→ 从此刻起算沉默
  useEffect(() => {
    resetSilenceBase()
  }, [streaming, tts.playing, resetSilenceBase])

  // 安卓音频路由保护：活跃麦克风会让 Chrome 切到"通话模式"（像打电话），把 TTS 路由到听筒且切不回扬声器。
  // 对策：播放期间释放麦克风（保证走扬声器）；播放结束 600ms 后再预热（保证下次录音秒开）。
  // 无问候语播放时，本 effect 在挂载后即完成首次预热（替代原"挂载即预热"，还避免与问候语抢路由）
  useEffect(() => {
    if (tts.playing) {
      releaseStream()
    } else if (hasConsent()) {
      const timer = setTimeout(() => warmUpMic(), 600)
      return () => clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tts.playing])

  // 录音 30 秒上限：到时自动停止并发送（等价于松手），防止长时间占用麦克风
  // 用 ref 持有最新 stopRecording，计时器只随 recording 变化重置，不被每次渲染重置
  const stopRecordingRef = useRef(null)
  useEffect(() => {
    stopRecordingRef.current = stopRecording
  })
  useEffect(() => {
    if (!recording) return
    const timer = setTimeout(() => stopRecordingRef.current?.(), 30000)
    return () => clearTimeout(timer)
  }, [recording])

  /** 启动一次语音会话：并行开启 MediaRecorder（上传情感分析）+ 浏览器 SpeechRecognition（降级转写 + 实时展示） */
  const startVoiceSession = useCallback(() => {
    // 并行启动浏览器 SpeechRecognition 作为降级转写 + 录音遮罩实时展示
    browserTranscriptRef.current = ''
    setLiveTranscript('')
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    if (SpeechRecognition) {
      try {
        const rec = new SpeechRecognition()
        rec.lang = 'zh-CN'
        rec.continuous = true
        rec.interimResults = true
        rec.onresult = (e) => {
          // 根因修复：Android Chrome 中文识别 continuous 模式下，
          // 语句结束后可能在 results 列表中产生内容相同的重复 final 条目。
          // 修复策略：只拼接 final 结果 + 跳过连续相同文本；interim 仅用于实时展示。
          let finalTranscript = ''
          let interimTranscript = ''
          let prevFinalText = ''
          for (let i = 0; i < e.results.length; i++) {
            const text = e.results[i][0].transcript
            if (e.results[i].isFinal) {
              // 跳过与前一个 final result 完全相同的条目（Android 重复 bug）
              if (text !== prevFinalText) {
                finalTranscript += text
                prevFinalText = text
              }
            } else {
              interimTranscript += text
            }
          }
          // 发送用 final（去重后）；实时展示用 final + 当前 interim
          browserTranscriptRef.current = finalTranscript || interimTranscript
          setLiveTranscript(finalTranscript + interimTranscript)
        }
        rec.onerror = () => {}
        rec.start()
        speechRecRef.current = rec
      } catch (err) {
        console.warn('浏览器语音识别启动失败', err)
      }
    }
    // 启动 MediaRecorder 录音
    startRecording()
  }, [startRecording])

  /* ===== 按住说话（微信同款）：按下录音、松开发送、上滑取消 ===== */

  /** 按下：开始录音（含授权检查；未授权则弹授权框，暂不录音） */
  const handleVoicePointerDown = useCallback((e) => {
    if (streaming || analyzing || recording) return
    if (!requestConsent()) return
    tts.stop() // 打断播放：用户按住麦克风要说话时 AI 应立即停读（也避免录音期间 TTS 被路由到听筒）
    e.currentTarget.setPointerCapture(e.pointerId)
    pointerStartYRef.current = e.clientY
    pointerDownTimeRef.current = Date.now()
    setCancelArmed(false)
    startVoiceSession()
  }, [streaming, analyzing, recording, requestConsent, startVoiceSession, tts.stop])

  /** 移动：上滑超过阈值进入取消态（松手即取消） */
  const handleVoicePointerMove = useCallback((e) => {
    if (!recording) return
    setCancelArmed(pointerStartYRef.current - e.clientY > 60)
  }, [recording])

  /** 松开：三分支——上滑取消 / 说话过短 / 正常发送（识别后自动发出） */
  const handleVoicePointerUp = useCallback(() => {
    if (!recording) return
    speechRecRef.current?.stop()
    const tooShort = Date.now() - pointerDownTimeRef.current < 1000
    if (cancelArmed) {
      cancelRecording()
      setVoiceNotice('已取消')
      setTimeout(() => setVoiceNotice(''), 2000)
    } else if (tooShort) {
      cancelRecording()
      setVoiceNotice('说话时间太短，按住说久一点 🎤')
      setTimeout(() => setVoiceNotice(''), 3000)
    } else {
      stopRecording() // → 识别 → 自动发送
    }
    setCancelArmed(false)
  }, [recording, cancelArmed, cancelRecording, stopRecording])

  /** 系统打断 / 指针捕获丢失：安全取消本次录音，避免卡在录音态 */
  const handleVoicePointerCancel = useCallback(() => {
    if (!recording) return
    speechRecRef.current?.stop()
    cancelRecording()
    setCancelArmed(false)
  }, [recording, cancelRecording])

  /** 授权通过：预热麦克风（下次按住秒开）；按住说话模式下不自动录音，需用户再次按住 */
  const handleConsentGrant = useCallback(async () => {
    grantConsent()
    await warmUpMic() // 先拿到麦克风流，首次录音也能秒开，避免漏录开头
    setVoiceNotice('按住麦克风开始说话 🎤')
    setTimeout(() => setVoiceNotice(''), 3000)
  }, [grantConsent, warmUpMic])

  /** 语音唤醒开关：开启时若未单独授权，先弹授权弹窗（授权通过后才真正开启） */
  const handleToggleWake = useCallback(() => {
    if (wakeEnabled) {
      setWakeEnabled(false)
      localStorage.setItem(WAKE_PREF_KEY, '0')
    } else if (wakeConsent.requestConsent()) {
      setWakeEnabled(true)
      localStorage.setItem(WAKE_PREF_KEY, '1')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wakeEnabled])

  /** 唤醒授权通过：记录授权 + 开启开关（进入待唤醒态） */
  const handleWakeConsentGrant = useCallback(() => {
    wakeConsent.grantConsent()
    setWakeEnabled(true)
    localStorage.setItem(WAKE_PREF_KEY, '1')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  /** 重播单条消息 */
  const handleReplay = useCallback((text, idx) => {
    setSpeakingMsgIdx(idx)
    releaseStream() // 释放麦克风，避免安卓把重播音频路由到听筒（合成窗口内完成扬声器切回）
    tts.speakSentence(text).then(() => setSpeakingMsgIdx(-1))
  }, [tts, releaseStream])

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

  const sendMessage = async (autoText?: string, autoEmotion?: string | null) => {
    const text = deduplicateText((autoText ?? input).trim())
    if (!text || streaming) return false

    // 先停止当前播放，再在用户手势中解锁音频（避免 unlock 被 stop 打断）
    tts.stop()
    tts.unlock()
    setSpeakingMsgIdx(-1)
    // 释放麦克风：安卓把活跃麦克风视为"通话"，会把 TTS 路由到听筒。
    // 在发送时（AI 生成前）就释放，给系统留足切回扬声器的时间，确保整段回复走扩音
    releaseStream()

    // 语音自动发送时用传入的 emotion（修复此前服务端情绪从未存入 state 的问题）；手动打字时用当前 state
    const emotion = autoEmotion !== undefined ? autoEmotion : voiceEmotion

    const body: Record<string, unknown> = { content: text }
    if (emotion) {
      body.voiceEmotion = emotion.labelEn
      body.voiceEmotionConfidence = emotion.confidence
      body.inputMode = 'voice'
    }

    const msgEmotion = emotion
    setInput('')
    setVoiceEmotion(null)
    // 冷场引导：孩子一说话即重置沉默计时 + 清零连续暖场计数
    recordInteraction()
    setMessages((prev) => [...prev, { role: 'user', content: text, emotion: msgEmotion }])
    setStreaming(true)
    // AI 回复挂上孩子情绪（语音情绪优先、会话情绪兜底），驱动情感化排印
    setMessages((prev) => [...prev, { role: 'assistant', content: '', emotion: msgEmotion || session.emotionTag }])

    let fullResponse = ''
    let timeoutChecker: ReturnType<typeof setInterval> | null = null

    // 流式 TTS：首句完成即开始合成播放，不等全文接收完
    if (!tts.muted) tts.startStreaming()

    try {
      const res = await authFetch(`/api/v1/chat/sessions/${session.sessionId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })

      if (!res.ok) throw new Error(`HTTP ${res.status}`)

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let lastDataTime = Date.now()

      // SSE 流超时保护：30s 无数据 → 自动结束（防止永远卡在 streaming 态）
      timeoutChecker = setInterval(() => {
        if (Date.now() - lastDataTime > 30000) {
          if (timeoutChecker) clearInterval(timeoutChecker)
          reader.cancel()
        }
      }, 5000)

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        lastDataTime = Date.now()

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const jsonStr = line.slice(5)
          try {
            const event = JSON.parse(jsonStr)
            if (event.type === 'token') {
              fullResponse += event.content
              // 流式 TTS：每个 token 喂入，首句完成即开始合成播放
              if (!tts.muted) tts.feedToken(event.content)
              setMessages((prev) => {
                const updated = [...prev]
                const last = updated[updated.length - 1]
                updated[updated.length - 1] = { ...last, content: last.content + event.content }
                return updated
              })
            } else if (event.type === 'risk') {
              // 风险事件是系统/心理老师的内部处理指令（如"允许继续 CBT 微干预，趋势观察"），
              // 不能展示给学生：孩子看不懂临床术语，且会意识到"被监控"而破坏辅导信任。
              // 风险数据由后端另行落库并推送教师后台；红色风险后端会单独下发孩子能懂的安抚语。
              // 这里仅记录日志便于调试，不渲染到聊天界面。
              console.info('[risk]', event.metadata?.riskLevel, event.content)
            }
          } catch { /* ignore parse errors */ }
        }
      }

    } catch (e) {
      // SSE 流读取异常。常见于流结束时 chunked 终止块缺失（服务端异步收尾不干净），
      // 此时 AI 回复其实已完整接收——保留已收到的内容，仅在完全没收到时才提示错误。
      if (fullResponse) {
        console.warn('SSE 流终止异常但回复已接收，忽略:', e?.message)
      } else {
        console.error('发送失败', e)
        setMessages((prev) => {
          const updated = [...prev]
          updated[updated.length - 1] = { role: 'assistant', content: '网络出了点问题，请再试一次哦 🙏', emotion: null }
          return updated
        })
      }
    } finally {
      if (timeoutChecker) clearInterval(timeoutChecker)
      // 流式 TTS 结束：冲刷剩余缓冲 + 等待播放完毕；无内容时重置状态
      if (!tts.muted) {
        if (fullResponse) {
          tts.endStreaming()
        } else {
          tts.stop()
        }
      }
      setStreaming(false)
    }
    return true
  }

  // 每次渲染后让 sendMessageRef 指向最新的 sendMessage（供定义在前的 handleRecordingComplete 调用）
  useEffect(() => {
    sendMessageRef.current = sendMessage
  })

  const [showSatisfaction, setShowSatisfaction] = useState(false)

  const handleEnd = () => {
    tts.stop()
    setShowSatisfaction(true)
  }

  const closeSession = async (rating?: number | null, comment?: string) => {
    setShowSatisfaction(false)
    try {
      const body = rating ? { rating, comment } : undefined
      await api(`/sessions/${session.sessionId}/close`, {
        method: 'POST',
        body: body ? JSON.stringify(body) : undefined,
      })
    } catch {
      // fallback: 旧接口
      try { await api(`/chat/sessions/${session.sessionId}/end`, { method: 'POST' }) } catch { /* ignore */ }
    }
    onEnd()
  }

  /* ===== 波波状态机（design/27 §4.3 + design/28 §1.1）：
     recording > streaming > tts.playing > 待唤醒(standby) > 会话窗聆听(active) > idle ===== */
  const boboState = recording ? 'listening'
    : streaming ? 'thinking'
    : tts.playing ? 'speaking'
    : voiceCall.mode === 'standby' ? 'waitingWake'
    : voiceCall.mode === 'active' ? 'listening'
    : 'idle'

  /* ===== 波波宠物（手机悬浮输入栏右上角 / Pad 左栏共用）— 按住说话 ===== */
  const boBoPet = (size, bubbleAlign = 'center') => (
    <BoBoPet
      state={boboState}
      colors={theme.bobo}
      sentenceText=""
      liveTranscript={liveTranscript}
      size={size}
      interactive={supported}
      cancelArmed={cancelArmed}
      disabled={streaming || analyzing}
      bubbleAlign={bubbleAlign}
      onPointerDown={handleVoicePointerDown}
      onPointerMove={handleVoicePointerMove}
      onPointerUp={handleVoicePointerUp}
      onPointerCancel={handleVoicePointerCancel}
    />
  )

  return (
    <div className="h-screen flex flex-col" style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
      {/* ===== Header ===== */}
      <header className="flex items-center justify-between px-4 lg:px-8 py-3 lg:py-4 bg-white/80 backdrop-blur border-b border-gray-100 shadow-sm">
        <div className="flex items-center gap-2 lg:gap-3">
          <span className="text-2xl lg:text-3xl">{theme.companion}</span>
          <span className="font-medium text-gray-800 lg:text-xl">{theme.companionName}</span>
        </div>
        <div className="flex items-center gap-2">
          {/* TTS 静音快捷按钮 */}
          <button
            onClick={tts.toggleMute}
            className={`p-2 lg:p-2.5 rounded-full transition-colors ${
              tts.muted ? 'text-gray-300' : 'text-[var(--primary)]'
            }`}
            title={tts.muted ? '开启语音' : '关闭语音'}
          >
            {tts.muted ? (
              <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
                <path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/>
              </svg>
            ) : (
              <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
                <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>
              </svg>
            )}
          </button>
          {/* 设置按钮 */}
          <button
            onClick={() => setSettingsOpen(true)}
            className="p-2 lg:p-2.5 rounded-full text-gray-400 hover:text-[var(--primary)] transition-colors"
            title="设置"
          >
            <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
            </svg>
          </button>
          {/* 切换同学（与设置并排，共享 Pad 场景） */}
          {onSwitchUser && (
            <button
              onClick={() => setConfirmSwitch(true)}
              className="flex items-center gap-1 px-3 py-1.5 lg:px-3.5 lg:py-2 rounded-full bg-orange-50 border border-orange-200 text-orange-600 hover:bg-orange-100 active:scale-95 transition-all"
              title="切换同学"
            >
              <span className="text-sm lg:text-base">🔄</span>
              <span className="text-xs lg:text-sm font-semibold">换人</span>
            </button>
          )}
          {/* 结束对话 */}
          <button
            onClick={handleEnd}
            className="text-sm lg:text-base px-4 py-2 lg:px-6 lg:py-3 rounded-full border border-gray-200
              text-gray-500 hover:text-red-500 hover:border-red-200 transition-colors"
          >
            结束
          </button>
        </div>
      </header>

      {/* ===== 主体：手机单栏 / Pad 双栏 ===== */}
      <div className="flex-1 flex overflow-hidden">

        {/* Pad 左栏：波波（伙伴 + 语音输入合一，design/27 §5.1） */}
        <aside className="hidden lg:flex flex-col items-center justify-center w-[340px] xl:w-[400px]
          border-r border-gray-100/50 p-8"
          style={{ background: 'linear-gradient(to bottom, var(--primary-light), var(--bg-end))' }}>
          {/* 波波（按住说话） */}
          <div className="mb-10">{boBoPet(170)}</div>
          <p className="text-lg" style={{ color: 'var(--primary)' }}>
            {recording ? '我在认真听你说...'
              : analyzing ? '我在感受你的情绪...'
              : streaming ? '让我想想...'
              : tts.playing ? '我在说给你听...'
              : voiceCall.wakeStatus === 'detected' ? '听到了！🎉'
              : voiceCall.wakeStatus === 'loading' ? '语音引擎加载中...'
              : voiceCall.wakeStatus === 'error' ? '语音引擎未就绪'
              : voiceCall.mode === 'standby' ? '叫我“哈喽波波”'
              : voiceCall.mode === 'active' ? '我在听，直接说吧'
              : '想说什么就说什么吧'}
          </p>
          <p className="mt-3 text-sm text-gray-400">
            {recording ? '松开手指发送，上滑取消'
              : voiceCall.wakeStatus === 'detected' ? '正在准备听你说话...'
              : voiceCall.mode === 'standby'
                ? (voiceCall.wakeStatus === 'loading' ? '正在加载语音引擎...' :
                   voiceCall.wakeStatus === 'listening' ? '我在这里安静地等你叫我' :
                   voiceCall.wakeStatus === 'error' ? '语音引擎加载失败，请关闭再开启' :
                   '我在这里安静地等你叫我')
              : voiceCall.mode === 'active' ? '不用按，直接说就行'
              : '按住波波，跟它说说话'}
          </p>
        </aside>

        {/* 右栏（手机为全宽）：对话区 */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* 消息列表 */}
          <main className="flex-1 overflow-y-auto p-4 lg:p-8 space-y-4 lg:space-y-5">
            {messages.map((msg, i) => (
              <MessageBubble
                key={i}
                msg={msg}
                isLast={i === messages.length - 1}
                streaming={streaming}
                onReplay={(text) => handleReplay(text, i)}
                isSpeaking={speakingMsgIdx === i || (tts.playing && i === messages.length - 1 && msg.role === 'assistant')}
              />
            ))}
            <div ref={bottomRef} />
          </main>

          {/* 输入区 */}
          <footer className="p-4 lg:px-8 lg:py-5 bg-white/80 backdrop-blur border-t border-gray-100">
            {/* 手机端语音唤醒状态指示器（Pad 在左栏已有，手机无左栏故需单独展示） */}
            {wakeEnabled && wakeConsent.hasConsent() && voiceCall.mode !== 'off' && !recording && !analyzing && (
              <div className="flex lg:hidden items-center justify-center gap-2 mb-3 text-xs">
                {voiceCall.wakeStatus === 'loading' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-blue-50 text-blue-500">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
                    </span>
                    语音引擎加载中...
                  </span>
                )}
                {voiceCall.wakeStatus === 'listening' && voiceCall.mode === 'standby' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-green-50 text-green-600">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                    </span>
                    说“哈喽波波”唤醒我
                  </span>
                )}
                {voiceCall.wakeStatus === 'detected' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-amber-50 text-amber-600">
                    🎉 听到了！正在准备听你说话...
                  </span>
                )}
                {voiceCall.mode === 'active' && voiceCall.wakeStatus !== 'detected' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-green-50 text-green-600">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                    </span>
                    我在听，直接说吧
                  </span>
                )}
                {voiceCall.wakeStatus === 'error' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-red-50 text-red-500">
                    语音引擎加载失败，可在设置中重试
                  </span>
                )}
              </div>
            )}
            {/* 语音降级提示 */}
            {voiceNotice && (
              <div className="flex items-center justify-center gap-2 mb-3 px-4 py-2 rounded-xl bg-amber-50 text-amber-700 text-sm">
                <span>💡</span>
                <span>{voiceNotice}</span>
              </div>
            )}

            {/* 手机端识别状态（录音态由全屏遮罩接管） */}
            {analyzing && (
              <div className="flex lg:hidden items-center justify-center gap-2 mb-3 text-sm" style={{ color: 'var(--primary)' }}>
                <span className="relative flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75" style={{ background: 'var(--primary)' }}></span>
                  <span className="relative inline-flex rounded-full h-3 w-3" style={{ background: 'var(--primary)' }}></span>
                </span>
                正在识别，马上发送...
              </div>
            )}

            {/* 手机语音情绪预览（voiceEmotion 当前流程中始终为 null，保留状态供后续扩展） */}

            <div className="relative flex gap-3 max-w-lg lg:max-w-2xl mx-auto items-center">
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && sendMessage()}
                placeholder={recording ? '正在录音...' : analyzing ? '分析中...' : '也可以打字告诉我'}
                disabled={streaming || analyzing}
                className="flex-1 px-5 py-3.5 lg:py-4 rounded-full border border-gray-200 focus:outline-none
                  focus:ring-2 text-sm lg:text-lg disabled:bg-gray-50"
                style={{ '--tw-ring-color': 'var(--primary-light)' } as React.CSSProperties}
              />
              <button
                onClick={() => sendMessage()}
                disabled={!input.trim() || streaming || analyzing}
                className="flex-shrink-0 px-6 lg:px-10 py-3.5 lg:py-4 rounded-full text-white
                  text-sm lg:text-lg font-medium active:scale-95
                  disabled:bg-gray-300 disabled:cursor-not-allowed transition-all"
                style={{ background: input.trim() && !streaming ? 'var(--primary)' : undefined }}
              >
                发送
              </button>
            </div>
          </footer>
        </div>
      </div>

      {/* 手机端可拖拽悬浮语音按钮（design/27 §5.4） */}
      {supported && (
        <div className="lg:hidden">
          <DraggableVoiceButton
            disabled={streaming || analyzing}
            onPointerDown={handleVoicePointerDown}
            onPointerMove={handleVoicePointerMove}
            onPointerUp={handleVoicePointerUp}
            onPointerCancel={handleVoicePointerCancel}
          >
            {(side) => boBoPet(60, side)}
          </DraggableVoiceButton>
        </div>
      )}

      {/* 语音授权弹窗（合规） */}
      {showConsent && (
        <VoiceConsentDialog onGrant={handleConsentGrant} onDeny={denyConsent} />
      )}

      {/* 语音唤醒单独授权弹窗（合规，design/28 §1.4） */}
      {wakeConsent.showDialog && (
        <VoiceCallConsentDialog onGrant={handleWakeConsentGrant} onDeny={wakeConsent.denyConsent} />
      )}

      {/* 结束会话满意度评价 */}
      {showSatisfaction && (
        <SatisfactionDialog
          onSubmit={(rating, comment) => closeSession(rating, comment)}
          onSkip={() => closeSession(null)}
          onResume={() => setShowSatisfaction(false)}
        />
      )}

      {/* 设置面板 */}
      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        muted={tts.muted}
        onToggleMute={tts.toggleMute}
        wakeSupported={voiceCall.wakeSupported}
        wakeOn={wakeEnabled}
        onToggleWake={handleToggleWake}
      />
      {/* 切换同学二次确认 */}
      <ConfirmDialog
        open={confirmSwitch}
        emoji="👋"
        title="要退出让别的同学用吗？"
        message="退出后需要重新登录哦"
        confirmText="确认退出"
        danger
        onConfirm={() => { setConfirmSwitch(false); onSwitchUser?.() }}
        onCancel={() => setConfirmSwitch(false)}
      />
    </div>
  )
}
