import { useState, useRef, useEffect, useCallback } from 'react'

/** Web Speech API 语音识别 Hook */
function useVoiceInput(onResult) {
  const [listening, setListening] = useState(false)
  const [supported, setSupported] = useState(true)
  const recognitionRef = useRef(null)

  useEffect(() => {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
    if (!SpeechRecognition) {
      setSupported(false)
      return
    }
    const recognition = new SpeechRecognition()
    recognition.lang = 'zh-CN'
    recognition.interimResults = true
    recognition.continuous = false
    recognition.maxAlternatives = 1

    recognition.onresult = (event) => {
      let transcript = ''
      for (let i = 0; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript
      }
      onResult(transcript)
    }
    recognition.onend = () => setListening(false)
    recognition.onerror = () => setListening(false)
    recognitionRef.current = recognition

    return () => recognition.abort()
  }, [onResult])

  const toggle = useCallback(() => {
    if (!recognitionRef.current) return
    if (listening) {
      recognitionRef.current.stop()
      setListening(false)
    } else {
      recognitionRef.current.start()
      setListening(true)
    }
  }, [listening])

  return { listening, supported, toggle }
}

export default function ChatRoom({ session, onEnd }) {
  const [messages, setMessages] = useState([
    { role: 'assistant', content: session.greeting },
  ])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const bottomRef = useRef(null)

  const handleVoiceResult = useCallback((transcript) => {
    setInput(transcript)
  }, [])

  const { listening, supported, toggle: toggleVoice } = useVoiceInput(handleVoiceResult)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = async () => {
    const text = input.trim()
    if (!text || streaming) return

    setInput('')
    setMessages((prev) => [...prev, { role: 'user', content: text }])
    setStreaming(true)

    // 添加空的 assistant 消息用于流式填充
    setMessages((prev) => [...prev, { role: 'assistant', content: '' }])

    try {
      const res = await fetch(`/api/v1/chat/sessions/${session.sessionId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: text }),
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
              // 风险事件：插入系统提示
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
      await fetch(`/api/v1/chat/sessions/${session.sessionId}`, { method: 'DELETE' })
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
                {msg.content || (streaming && i === messages.length - 1 ? '...' : '')}
              </div>
            )}
          </div>
        ))}
        <div ref={bottomRef} />
      </main>

      {/* Input */}
      <footer className="p-4 bg-white border-t border-gray-100">
        {/* 录音状态提示 */}
        {listening && (
          <div className="flex items-center justify-center gap-2 mb-3 text-sm text-indigo-600">
            <span className="relative flex h-3 w-3">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-3 w-3 bg-indigo-500"></span>
            </span>
            正在听你说... 再点一下结束
          </div>
        )}
        <div className="flex gap-2 max-w-lg mx-auto items-center">
          {/* 语音按钮 */}
          {supported && (
            <button
              onClick={toggleVoice}
              disabled={streaming}
              className={`flex-shrink-0 w-11 h-11 rounded-full flex items-center justify-center transition-all
                ${listening
                  ? 'bg-red-500 text-white shadow-lg shadow-red-200 scale-110'
                  : 'bg-gray-100 text-gray-600 hover:bg-indigo-100 hover:text-indigo-600'
                } disabled:opacity-40 disabled:cursor-not-allowed`}
              title={listening ? '停止录音' : '语音输入'}
            >
              {listening ? (
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
            placeholder={listening ? '正在聆听...' : '想说什么就说什么，也可以点麦克风说'}
            disabled={streaming}
            className="flex-1 px-4 py-3 rounded-full border border-gray-200 focus:outline-none focus:border-indigo-300 focus:ring-2 focus:ring-indigo-100 text-sm disabled:bg-gray-50"
          />
          <button
            onClick={sendMessage}
            disabled={!input.trim() || streaming}
            className="flex-shrink-0 px-5 py-3 rounded-full bg-indigo-500 text-white text-sm font-medium
              hover:bg-indigo-600 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
          >
            发送
          </button>
        </div>
      </footer>
    </div>
  )
}
