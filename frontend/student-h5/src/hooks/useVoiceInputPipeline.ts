import { useState, useRef, useEffect, useCallback } from 'react'
import { useAudioRecorder } from './useAudioRecorder'
import { fetchVoiceAnalyze } from '../api'
import type { VoiceEmotion } from './useChatSession'

/**
 * 语音输入流水线 Hook（ARCH-006 F-4，doing/66 §3.1）
 *
 * 将 ChatRoom 内联的语音编排链（按住说话 → 录音 → 上传分析 → 自动发送）抽取为单一 hook：
 *   start()  → 并行启动 MediaRecorder（上传情感分析）+ SpeechRecognition（降级转写 + 实时展示）
 *   stop()   → 松手发送（<1000ms 视为误触 → 取消 + 提示）
 *   cancel() → 上滑取消（丢弃音频，不发送）
 *   录音完成 → fetchVoiceAnalyze 上传 → onTranscription(text, emotion) 自动发送
 *   失败链路 → 三级降级：服务端分析 → 浏览器转写 → 温和提示
 *
 * 状态机：IDLE → RECORDING → ANALYZING → SENDING → IDLE（ERROR/TIMEOUT 为分支态）
 * isRecording/isAnalyzing/supported 直接透传 useAudioRecorder（单一事实源）。
 */
export function useVoiceInputPipeline({ onTranscription }: {
  onTranscription: (text: string, emotion: VoiceEmotion | null) => void
}) {
  /** 录音完成 → 上传分析 → 自动发送（audioBlob 为 null 时直接用浏览器转写） */
  const handleComplete = useCallback(async (audioBlob: Blob | null) => {
    // 无录音（麦克风不可用）→ 直接用浏览器识别结果
    if (!audioBlob) {
      const text = browserTranscriptRef.current
      if (text) {
        setError('已用浏览器识别（语音情绪分析暂不可用）')
        onTranscription(text, null)
      } else {
        setError('没有听清，请再说一次或打字告诉我 ✏️')
      }
      return
    }

    const formData = new FormData()
    formData.append('file', audioBlob, 'recording.webm')

    setIsSending(true)
    try {
      // F-2 端点收敛：具名 authFetch 接缝（ARCH-005），401 自动刷新+重放
      const res = await fetchVoiceAnalyze(formData)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const json = await res.json()
      if (json.success && json.data) {
        const { text, emotion } = json.data
        if (text) {
          // 自动发送：emotion 直接传给 onTranscription（修复此前情绪从未存入 state、预览不显示的问题）
          onTranscription(text, emotion)
          return
        }
      }
      throw new Error('服务端未返回文字')
    } catch (err) {
      console.warn('语音分析服务不可用，尝试浏览器识别', (err as Error).message)
      // 降级：使用浏览器 Web Speech API 实时识别结果
      const text = browserTranscriptRef.current
      if (text) {
        setError('已用浏览器识别（语音情绪分析暂不可用）')
        onTranscription(text, null)
      } else {
        setError('语音识别暂不可用，请打字告诉我吧 ✏️')
      }
    } finally {
      setIsSending(false)
    }
  }, [onTranscription])

  // —— 组合 useAudioRecorder（录音 + 麦克风预热/释放 + supported 检测）——
  const { recording, analyzing, supported, startRecording, stopRecording, cancelRecording, warmUp, releaseStream } =
    useAudioRecorder(handleComplete)

  /** 发送瞬态（上传分析期间为 true；同步置位+清位，React 批处理下表现为瞬态） */
  const [isSending, setIsSending] = useState(false)
  /** 语音链路提示文案（由消费方负责定时清空展示，如 voiceNotice） */
  const [error, setError] = useState<string | null>(null)
  /** 实时转写（final + interim 拼接，用于录音遮罩展示） */
  const [liveTranscript, setLiveTranscript] = useState('')

  // ref 镜像：防重入判定需要同步读取最新 recording（recorder 状态来自外部 hook）
  const recordingRef = useRef(false)
  useEffect(() => { recordingRef.current = recording })

  const speechRecRef = useRef<{ stop: () => void } | null>(null)
  /** 浏览器转写（发送用 final 去重结果；interim 仅实时展示） */
  const browserTranscriptRef = useRef('')
  /** 按住起始时间戳（过短判定 <1000ms） */
  const startTimeRef = useRef(0)

  // 录音 30 秒上限：到时自动停止并发送（等价于松手），防止长时间占用麦克风
  // 用 ref 持有最新 stopRecording，计时器只随 recording 变化重置，不被每次渲染重置
  const stopRecordingRef = useRef(stopRecording)
  useEffect(() => { stopRecordingRef.current = stopRecording })
  useEffect(() => {
    if (!recording) return
    const timer = setTimeout(() => stopRecordingRef.current(), 30000)
    return () => clearTimeout(timer)
  }, [recording])

  /** 启动一次语音会话：并行开启 MediaRecorder（上传情感分析）+ 浏览器 SpeechRecognition（降级转写 + 实时展示） */
  const start = useCallback(() => {
    if (recordingRef.current) return // 防重入：录音中不重复启动
    setError(null)
    // 并行启动浏览器 SpeechRecognition 作为降级转写 + 录音遮罩实时展示
    browserTranscriptRef.current = ''
    setLiveTranscript('')
    startTimeRef.current = Date.now()
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    if (SpeechRecognition) {
      try {
        const rec = new SpeechRecognition()
        rec.lang = 'zh-CN'
        rec.continuous = true
        rec.interimResults = true
        rec.onresult = (e: any) => {
          // 根因修复：Android Chrome 中文识别 continuous 模式下，
          // 语句结束后可能在 results 列表中产生内容相同的重复 final 条目。
          // 修复策略：只拼接 final 结果 + 跳过连续相同文本；interim 仅用于实时展示。
          let displayTranscript = ''
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
                displayTranscript += text
              }
            } else {
              interimTranscript += text
              displayTranscript += text
            }
          }
          // 发送用 final（去重后）；实时展示用 final + 当前 interim
          browserTranscriptRef.current = finalTranscript || interimTranscript
          setLiveTranscript(displayTranscript)
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

  /** 松手发送：停止识别 → 过短判定（<1000ms 视为误触）→ 正常则停止录音进入分析链 */
  const stop = useCallback(() => {
    speechRecRef.current?.stop()
    speechRecRef.current = null
    const tooShort = Date.now() - startTimeRef.current < 1000
    if (tooShort) {
      cancelRecording()
      setError('说话时间太短，按住说久一点 🎤')
    } else {
      stopRecording() // → 录音完成 → 上传 → 自动发送
    }
  }, [cancelRecording, stopRecording])

  /** 上滑取消：停止识别 + 丢弃录音，不触发发送 */
  const cancel = useCallback(() => {
    speechRecRef.current?.stop()
    speechRecRef.current = null
    cancelRecording()
  }, [cancelRecording])

  return {
    isRecording: recording,
    isAnalyzing: analyzing,
    isSending,
    supported,
    error,
    liveTranscript,
    warmUp,
    releaseStream,
    start,
    stop,
    cancel,
  }
}
