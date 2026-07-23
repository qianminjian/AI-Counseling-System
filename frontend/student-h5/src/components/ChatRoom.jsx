import { useState, useRef, useEffect, useCallback } from 'react'
import VoiceConsentDialog, { useVoiceConsent } from './VoiceConsentDialog'

/** 情绪标签 → emoji 映射 */
const EMOTION_EMOJI = {
  happy: '😊', sad: '😢', angry: '😠', fearful: '😨',
  neutral: '😐', surprised: '😲', disgusted: '🤢', unknown: '', other: '',
}

/**
 * MediaRecorder 录音 Hook（M2：录制真实音频用于情感分析）
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
      toggleVoice() // 停止录音不需要授权
    } else {
      if (requestConsent()) {
        toggleVoice() // 已授权，直接开始
      }
      // 未授权会弹出 dialog
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

    // 构建消息体（含语音情绪元数据）
    const body = { content: text }
    if (voiceEmotion) {
      body.voiceEmotion = voiceEmotion.labelEn
      body.voiceEmotionConfidence = voiceEmotion.confidence
      body.inputMode = 'voice'
    }

    const msgEmotion = voiceEmotion // 保存当前情绪用于气泡显示
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

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* Header */}
      <header className="flex items-center justify-between px-4 py-3 bg-white border-b border-gray-100 shadow-sm">
        <div className="flex items-center gap-2">
          <span className="text-2xl">🤗</span>
          <span className="font-medium text-gray-800">心理小伙伴</span>
        </div>
        <button
          onClick={handleEnd}
          className="text-sm text-gray-400 hover:text-red-500 transition-colors"
        >
          结束对话
        </button>
      </header>

      {/* Messages */}
      <main className="flex-1 overflow-y-auto p-4 space-y-4">
        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            {msg.role === 'system' ? (
              <div className={`w-full text-center py-2 px-3 rounded-lg text-sm
                ${msg.level >= 3 ? 'bg-red-50 text-red-600' : 'bg-amber-50 text-amber-600'}`}>
                {msg.content}
              </div>
            ) : (
              <div className={`max-w-[80%] px-4 py-3 rounded-2xl text-sm leading-relaxed whitespace-pre-wrap
                ${msg.role === 'user'
                  ? 'bg-indigo-500 text-white rounded-br-md'
                  : 'bg-white text-gray-700 border border-gray-100 shadow-sm rounded-bl-md'
                }`}>
                {/* 语音情绪标签 */}
                {msg.emotion && msg.emotion.labelEn !== 'unknown' && (
                  <span className="inline-block mr-1 text-xs opacity-80">
                    {EMOTION_EMOJI[msg.emotion.labelEn] || '🎵'}
                  </span>
                )}
                {msg.content || (streaming && i === messages.length - 1 ? '...' : '')}
              </div>
            )}
          </div>
        ))}
        <div ref={bottomRef} />
      </main>

      {/* Input */}
      <footer className="p-4 bg-white border-t border-gray-100">
        {/* 录音/分析状态提示 */}
        {(recording || analyzing) && (
          <div className="flex items-center justify-center gap-2 mb-3 text-sm text-indigo-600">
            <span className="relative flex h-3 w-3">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-3 w-3 bg-indigo-500"></span>
            </span>
            {recording ? '正在录音... 再点一下结束' : '正在分析语音情绪...'}
          </div>
        )}

        {/* 语音情绪预览 */}
        {voiceEmotion && (
          <div className="flex items-center justify-center gap-1 mb-2 text-xs text-gray-500">
            <span>{EMOTION_EMOJI[voiceEmotion.labelEn] || '🎵'}</span>
            <span>语音情绪：{voiceEmotion.label}（{Math.round(voiceEmotion.confidence * 100)}%）</span>
          </div>
        )}

        <div className="flex gap-2 max-w-lg mx-auto items-center">
          {/* 语音按钮 */}
          {supported && (
            <button
              onClick={handleVoiceClick}
              disabled={streaming || analyzing}
              className={`flex-shrink-0 w-11 h-11 rounded-full flex items-center justify-center transition-all
                ${recording
                  ? 'bg-red-500 text-white shadow-lg shadow-red-200 scale-110'
                  : 'bg-gray-100 text-gray-600 hover:bg-indigo-100 hover:text-indigo-600'
                } disabled:opacity-40 disabled:cursor-not-allowed`}
              title={recording ? '停止录音' : '语音输入（含情绪分析）'}
            >
              {recording ? (
                <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              ) : (
                <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
                  <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
                </svg>
              )}
            </button>
          )}
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && sendMessage()}
            placeholder={recording ? '正在录音...' : analyzing ? '分析中...' : '点麦克风说话，或打字也行'}
            disabled={streaming || analyzing}
            className="flex-1 px-4 py-3 rounded-full border border-gray-200 focus:outline-none focus:border-indigo-300 focus:ring-2 focus:ring-indigo-100 text-sm disabled:bg-gray-50"
          />
          <button
            onClick={sendMessage}
            disabled={!input.trim() || streaming || analyzing}
            className="flex-shrink-0 px-5 py-3 rounded-full bg-indigo-500 text-white text-sm font-medium
              hover:bg-indigo-600 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
          >
            发送
          </button>
        </div>
      </footer>

      {/* 语音授权弹窗（合规） */}
      {showConsent && (
        <VoiceConsentDialog onGrant={handleConsentGrant} onDeny={denyConsent} />
      )}
    </div>
  )
}
