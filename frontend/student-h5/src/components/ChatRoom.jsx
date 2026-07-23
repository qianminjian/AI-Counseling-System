import { useState, useRef, useEffect, useCallback } from 'react'
import VoiceConsentDialog, { useVoiceConsent } from './VoiceConsentDialog'

/** 情绪标签 → emoji 映射 */
const EMOTION_EMOJI = {
  happy: '😊', sad: '😢', angry: '😠', fearful: '😨',
  neutral: '😐', surprised: '😲', disgusted: '🤢', unknown: '', other: '',
}

/**
 * MediaRecorder 录音 Hook（录制真实音频用于情感分析）
 */
function useAudioRecorder(onComplete) {
  const [recording, setRecording] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [supported, setSupported] = useState(true)
  const mediaRecorderRef = useRef(null)
  const chunksRef = useRef([])
  const streamRef = useRef(null)

  useEffect(() => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setSupported(false)
    }
    return () => {
      streamRef.current?.getTracks().forEach(t => t.stop())
    }
  }, [])

  const startRecording = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      streamRef.current = stream
      chunksRef.current = []

      const recorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data)
      }
      recorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop())
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
        setAnalyzing(true)
        try {
          await onComplete(blob)
        } finally {
          setAnalyzing(false)
        }
      }
      recorder.start()
      mediaRecorderRef.current = recorder
      setRecording(true)
    } catch (err) {
      console.error('麦克风访问失败', err)
      setSupported(false)
    }
  }, [onComplete])

  const stopRecording = useCallback(() => {
    if (mediaRecorderRef.current && recording) {
      mediaRecorderRef.current.stop()
      setRecording(false)
    }
  }, [recording])

  const toggle = useCallback(() => {
    if (recording) stopRecording()
    else startRecording()
  }, [recording, startRecording, stopRecording])

  return { recording, analyzing, supported, toggle }
}

/** 消息气泡组件（手机/Pad 共用） */
function MessageBubble({ msg, isLast, streaming }) {
  if (msg.role === 'system') {
    return (
      <div className="flex justify-start lg:justify-center">
        <div className={`w-full lg:w-auto text-center py-2 px-3 rounded-lg text-sm lg:text-base
          ${msg.level >= 3 ? 'bg-red-50 text-red-600' : 'bg-amber-50 text-amber-600'}`}>
          {msg.content}
        </div>
      </div>
    )
  }
  return (
    <div className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[85%] lg:max-w-[70%] px-4 py-3 lg:px-5 lg:py-4 rounded-2xl lg:rounded-3xl
        text-sm lg:text-lg leading-relaxed whitespace-pre-wrap
        ${msg.role === 'user'
          ? 'bg-indigo-500 text-white rounded-br-md'
          : 'bg-white text-gray-700 border border-gray-100 shadow-sm rounded-bl-md'
        }`}>
        {msg.emotion && msg.emotion.labelEn !== 'unknown' && (
          <span className="inline-block mr-1 text-xs lg:text-sm opacity-80">
            {EMOTION_EMOJI[msg.emotion.labelEn] || '🎵'}
          </span>
        )}
        {msg.content || (streaming && isLast ? '...' : '')}
      </div>
    </div>
  )
}

export default function ChatRoom({ session, onEnd }) {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: session.greeting },
  ])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [voiceEmotion, setVoiceEmotion] = useState(null)
  const bottomRef = useRef(null)

  // 语音授权（合规）
  const { showDialog: showConsent, requestConsent, grantConsent, denyConsent } = useVoiceConsent()

  /** 录音完成 → 上传分析 → 填充文字 + 情绪 */
  const handleRecordingComplete = useCallback(async (audioBlob) => {
    const formData = new FormData()
    formData.append('file', audioBlob, 'recording.webm')

    try {
      const res = await fetch('/api/v1/voice/analyze', { method: 'POST', body: formData })
      const json = await res.json()
      if (json.success && json.data) {
        const { text, emotion } = json.data
        if (text) setInput(text)
        if (emotion && emotion.labelEn !== 'unknown') {
          setVoiceEmotion(emotion)
        }
      }
    } catch (err) {
      console.error('语音分析失败（降级为手动输入）', err)
    }
  }, [])

  const { recording, analyzing, supported, toggle: toggleVoice } = useAudioRecorder(handleRecordingComplete)

  /** 语音按钮点击（含授权检查） */
  const handleVoiceClick = useCallback(() => {
    if (recording) {
      toggleVoice()
    } else {
      if (requestConsent()) {
        toggleVoice()
      }
    }
  }, [recording, toggleVoice, requestConsent])

  /** 授权通过后自动开始录音 */
  const handleConsentGrant = useCallback(() => {
    grantConsent()
    toggleVoice()
  }, [grantConsent, toggleVoice])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = async () => {
    const text = input.trim()
    if (!text || streaming) return

    const body = { content: text }
    if (voiceEmotion) {
      body.voiceEmotion = voiceEmotion.labelEn
      body.voiceEmotionConfidence = voiceEmotion.confidence
      body.inputMode = 'voice'
    }

    const msgEmotion = voiceEmotion
    setInput('')
    setVoiceEmotion(null)
    setMessages((prev) => [...prev, { role: 'user', content: text, emotion: msgEmotion }])
    setStreaming(true)
    setMessages((prev) => [...prev, { role: 'assistant', content: '' }])

    try {
      const res = await fetch(`/api/v1/chat/sessions/${session.sessionId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })

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
          const jsonStr = line.slice(5)
          try {
            const event = JSON.parse(jsonStr)
            if (event.type === 'token') {
              setMessages((prev) => {
                const updated = [...prev]
                const last = updated[updated.length - 1]
                updated[updated.length - 1] = { ...last, content: last.content + event.content }
                return updated
              })
            } else if (event.type === 'risk') {
              setMessages((prev) => [
                ...prev.slice(0, -1),
                { role: 'system', content: `⚠️ ${event.content}`, level: event.metadata?.riskLevel },
                ...prev.slice(-1),
              ])
            }
          } catch { /* ignore parse errors */ }
        }
      }
    } catch (e) {
      console.error('发送失败', e)
      setMessages((prev) => {
        const updated = [...prev]
        updated[updated.length - 1] = { role: 'assistant', content: '网络出了点问题，请再试一次哦 🙏' }
        return updated
      })
    } finally {
      setStreaming(false)
    }
  }

  const handleEnd = async () => {
    try {
      await fetch(`/api/v1/chat/sessions/${session.sessionId}/end`, { method: 'POST' })
    } catch { /* ignore */ }
    onEnd()
  }

  /* ===== 语音大按钮（Pad 左栏 / 手机底部共用逻辑） ===== */
  const voiceButton = (size) => {
    const sizeClass = size === 'lg'
      ? 'w-28 h-28 lg:w-36 lg:h-36'
      : 'w-12 h-12'
    const iconClass = size === 'lg' ? 'w-12 h-12 lg:w-14 lg:h-14' : 'w-5 h-5'

    return (
      <button
        onClick={handleVoiceClick}
        disabled={streaming || analyzing}
        className={`${sizeClass} rounded-full flex items-center justify-center transition-all flex-shrink-0
          ${recording
            ? 'bg-red-500 text-white shadow-xl shadow-red-200 scale-110 animate-pulse'
            : 'bg-indigo-500 text-white shadow-lg shadow-indigo-200 hover:bg-indigo-600 active:scale-95'
          } disabled:opacity-40 disabled:cursor-not-allowed`}
        title={recording ? '停止录音' : '按住说话'}
      >
        {recording ? (
          <svg className={iconClass} fill="currentColor" viewBox="0 0 24 24">
            <rect x="6" y="6" width="12" height="12" rx="2" />
          </svg>
        ) : (
          <svg className={iconClass} fill="currentColor" viewBox="0 0 24 24">
            <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
            <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
          </svg>
        )}
      </button>
    )
  }

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* ===== Header ===== */}
      <header className="flex items-center justify-between px-4 lg:px-8 py-3 lg:py-4 bg-white border-b border-gray-100 shadow-sm">
        <div className="flex items-center gap-2 lg:gap-3">
          <span className="text-2xl lg:text-3xl">🤗</span>
          <span className="font-medium text-gray-800 lg:text-xl">心理小伙伴</span>
        </div>
        <button
          onClick={handleEnd}
          className="text-sm lg:text-base px-4 py-2 lg:px-6 lg:py-3 rounded-full border border-gray-200
            text-gray-500 hover:text-red-500 hover:border-red-200 transition-colors"
        >
          结束对话
        </button>
      </header>

      {/* ===== 主体：手机单栏 / Pad 双栏 ===== */}
      <div className="flex-1 flex overflow-hidden">

        {/* Pad 左栏：伙伴区 + 语音主按钮（仅 lg 显示） */}
        <aside className="hidden lg:flex flex-col items-center justify-center w-[340px] xl:w-[400px]
          bg-gradient-to-b from-indigo-50 to-purple-50 border-r border-indigo-100 p-8">
          {/* 伙伴形象 */}
          <div className={`text-8xl xl:text-9xl mb-6 transition-transform duration-300 ${streaming ? 'animate-bounce' : ''}`}>
            {recording ? '👂' : streaming ? '🤔' : '🤗'}
          </div>
          <p className="text-lg text-indigo-400 mb-10">
            {recording ? '我在认真听你说...' : analyzing ? '我在感受你的情绪...' : streaming ? '让我想想...' : '想说什么就说什么吧'}
          </p>

          {/* 语音大按钮 */}
          {supported && voiceButton('lg')}
          <p className="mt-4 text-sm text-gray-400">
            {recording ? '再点一下结束录音' : '点麦克风，用说的更快哦'}
          </p>

          {/* 语音情绪预览 */}
          {voiceEmotion && (
            <div className="mt-6 flex items-center gap-2 px-4 py-2 bg-white rounded-full shadow-sm text-sm text-gray-600">
              <span>{EMOTION_EMOJI[voiceEmotion.labelEn] || '🎵'}</span>
              <span>我感觉到你有点{voiceEmotion.label}</span>
            </div>
          )}
        </aside>

        {/* 右栏（手机为全宽）：对话区 */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* 消息列表 */}
          <main className="flex-1 overflow-y-auto p-4 lg:p-8 space-y-4 lg:space-y-5">
            {messages.map((msg, i) => (
              <MessageBubble key={i} msg={msg} isLast={i === messages.length - 1} streaming={streaming} />
            ))}
            <div ref={bottomRef} />
          </main>

          {/* 输入区 */}
          <footer className="p-4 lg:px-8 lg:py-5 bg-white border-t border-gray-100">
            {/* 手机录音状态 */}
            {(recording || analyzing) && (
              <div className="flex lg:hidden items-center justify-center gap-2 mb-3 text-sm text-indigo-600">
                <span className="relative flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-indigo-500"></span>
                </span>
                {recording ? '正在录音... 再点一下结束' : '正在分析语音情绪...'}
              </div>
            )}

            {/* 手机语音情绪预览 */}
            {voiceEmotion && (
              <div className="flex lg:hidden items-center justify-center gap-1 mb-2 text-xs text-gray-500">
                <span>{EMOTION_EMOJI[voiceEmotion.labelEn] || '🎵'}</span>
                <span>语音情绪：{voiceEmotion.label}（{Math.round(voiceEmotion.confidence * 100)}%）</span>
              </div>
            )}

            <div className="flex gap-3 max-w-lg lg:max-w-2xl mx-auto items-center">
              {/* 手机端语音按钮（Pad 用左栏大按钮） */}
              {supported && (
                <div className="lg:hidden">{voiceButton('sm')}</div>
              )}
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && sendMessage()}
                placeholder={recording ? '正在录音...' : analyzing ? '分析中...' : '也可以打字告诉我'}
                disabled={streaming || analyzing}
                className="flex-1 px-5 py-3.5 lg:py-4 rounded-full border border-gray-200 focus:outline-none
                  focus:border-indigo-300 focus:ring-2 focus:ring-indigo-100 text-sm lg:text-lg disabled:bg-gray-50"
              />
              <button
                onClick={sendMessage}
                disabled={!input.trim() || streaming || analyzing}
                className="flex-shrink-0 px-6 lg:px-10 py-3.5 lg:py-4 rounded-full bg-indigo-500 text-white
                  text-sm lg:text-lg font-medium hover:bg-indigo-600 active:scale-95
                  disabled:bg-gray-300 disabled:cursor-not-allowed transition-all"
              >
                发送
              </button>
            </div>
          </footer>
        </div>
      </div>

      {/* 语音授权弹窗（合规） */}
      {showConsent && (
        <VoiceConsentDialog onGrant={handleConsentGrant} onDeny={denyConsent} />
      )}
    </div>
  )
}
