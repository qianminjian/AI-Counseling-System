import { useState, useRef, useEffect, useCallback } from 'react'

/** 检测浏览器支持的录音格式（iOS Safari 不支持 webm，只支持 mp4/aac） */
function getSupportedMimeType() {
  if (typeof MediaRecorder === 'undefined' || !MediaRecorder.isTypeSupported) return ''
  const candidates = ['audio/webm', 'audio/mp4', 'audio/ogg', 'audio/aac']
  for (const type of candidates) {
    try {
      if (MediaRecorder.isTypeSupported(type)) return type
    } catch { /* ignore */ }
  }
  return '' // 让浏览器自行选择默认格式
}

/**
 * 语音录制 Hook
 * - 优先用 MediaRecorder 录真实音频（上传做情感分析）
 * - 麦克风不可用时降级为纯浏览器识别（SpeechRecognition，由父组件提供转写）
 * - supported：麦克风 或 浏览器语音识别 任一可用即为 true
 */
export function useAudioRecorder(onComplete: (blob: Blob | null) => Promise<unknown>) {
  const [recording, setRecording] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [supported, setSupported] = useState(true)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null)
  const mimeTypeRef = useRef('audio/webm')
  const chunksRef = useRef<Blob[]>([])
  const streamRef = useRef<MediaStream | null>(null)

  useEffect(() => {
    const hasMic = !!navigator.mediaDevices?.getUserMedia
    const hasSpeechRec = !!((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition)
    // 麦克风和浏览器识别都不可用才隐藏语音按钮
    if (!hasMic && !hasSpeechRec) {
      setSupported(false)
    }
    return () => {
      streamRef.current?.getTracks().forEach(t => t.stop())
    }
  }, [])

  /** 预热麦克风：提前获取 MediaStream 并保持，避免录音时 getUserMedia 异步延迟（300-500ms）漏录开头 */
  const warmingRef = useRef(false)
  const warmUp = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) return
    // 已有可用流则跳过
    if (streamRef.current && streamRef.current.getTracks().some(t => t.readyState === 'live')) return
    if (warmingRef.current) return // 预热进行中，避免重复获取流（StrictMode 双调用防护）
    warmingRef.current = true
    try {
      streamRef.current = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (err: any) {
      console.warn('麦克风预热失败（将在录音时重试）', err.name)
    } finally {
      warmingRef.current = false
    }
  }, [])

  const startRecording = useCallback(async () => {
    setRecording(true)
    chunksRef.current = []
    try {
      // 优先复用预热的麦克风流（秒开，避免 getUserMedia 异步延迟漏录开头的词）
      let stream = streamRef.current
      if (!stream || !stream.getTracks().some(t => t.readyState === 'live')) {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        streamRef.current = stream
      }
      const mimeType = getSupportedMimeType()
      mimeTypeRef.current = mimeType || 'audio/webm'
      const recorder = mimeType
        ? new MediaRecorder(stream, { mimeType })
        : new MediaRecorder(stream)
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data)
      }
      recorder.onstop = async () => {
        // 不停止麦克风流（保持预热供下次录音秒开），仅结束本次录音
        const blob = new Blob(chunksRef.current, { type: mimeTypeRef.current })
        setAnalyzing(true)
        try {
          await onComplete(blob)
        } finally {
          setAnalyzing(false)
        }
      }
      recorder.start()
      mediaRecorderRef.current = recorder
    } catch (err: any) {
      // 麦克风不可用（权限拒绝/非安全上下文）→ 降级为纯浏览器识别
      console.warn('麦克风不可用，将仅用浏览器识别', err.name)
      mediaRecorderRef.current = null
    }
  }, [onComplete])

  /** 松手发送：停止录音 → 触发 onstop → onComplete(blob) 上传识别 */
  const stopRecording = useCallback(() => {
    if (!recording) return
    setRecording(false)
    if (mediaRecorderRef.current) {
      // 有录音 → 停止后触发 onstop → onComplete(blob)
      mediaRecorderRef.current.stop()
      mediaRecorderRef.current = null
    } else {
      // 无录音（纯浏览器识别）→ 直接用浏览器转写结果
      setAnalyzing(true)
      Promise.resolve(onComplete(null)).finally(() => setAnalyzing(false))
    }
  }, [recording, onComplete])

  /** 上滑取消：停止录音但丢弃音频，不触发 onComplete（先摘掉 onstop 再 stop，ref 置空保证幂等） */
  const cancelRecording = useCallback(() => {
    if (!recording) return
    setRecording(false)
    if (mediaRecorderRef.current) {
      mediaRecorderRef.current.onstop = null
      mediaRecorderRef.current.stop()
      mediaRecorderRef.current = null
    }
  }, [recording])

  /** 释放麦克风流（TTS 播放期间必须释放：安卓把"活跃麦克风"视为通话，会把音频路由到听筒） */
  const releaseStream = useCallback(() => {
    streamRef.current?.getTracks().forEach(t => t.stop())
    streamRef.current = null
  }, [])

  return { recording, analyzing, supported, startRecording, stopRecording, cancelRecording, warmUp, releaseStream }
}
