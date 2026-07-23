import { useState } from 'react'

const EMOTIONS = [
  { tag: 'happy', emoji: '😊', label: '开心', color: 'bg-yellow-100 border-yellow-300 text-yellow-800' },
  { tag: 'sad', emoji: '😢', label: '难过', color: 'bg-blue-100 border-blue-300 text-blue-800' },
  { tag: 'angry', emoji: '😠', label: '生气', color: 'bg-red-100 border-red-300 text-red-800' },
  { tag: 'scared', emoji: '😨', label: '害怕', color: 'bg-purple-100 border-purple-300 text-purple-800' },
  { tag: 'nervous', emoji: '😰', label: '紧张', color: 'bg-orange-100 border-orange-300 text-orange-800' },
]

export default function EmotionSelect({ onStart }) {
  const [selected, setSelected] = useState(null)
  const [loading, setLoading] = useState(false)

  const handleStart = async () => {
    if (!selected) return
    setLoading(true)
    try {
      const res = await fetch('/api/v1/chat/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ emotionTag: selected, channel: 'h5' }),
      })
      const json = await res.json()
      if (json.success) {
        onStart({
          sessionId: json.data.sessionId,
          greeting: json.data.greeting,
          emotionTag: selected,
        })
      }
    } catch (e) {
      console.error('创建会话失败', e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-gradient-to-b from-indigo-50 to-white">
      <div className="text-6xl mb-4">🌈</div>
      <h1 className="text-2xl font-bold text-gray-800 mb-2">嗨，同学！</h1>
      <p className="text-gray-500 mb-8">今天你的心情怎么样呀？</p>

      <div className="grid grid-cols-3 gap-4 mb-8 max-w-sm w-full">
        {EMOTIONS.map((e) => (
          <button
            key={e.tag}
            onClick={() => setSelected(e.tag)}
            className={`flex flex-col items-center gap-2 p-4 rounded-2xl border-2 transition-all
              ${selected === e.tag
                ? e.color + ' scale-105 shadow-md'
                : 'bg-white border-gray-200 hover:border-gray-300'
              }`}
          >
            <span className="text-3xl">{e.emoji}</span>
            <span className="text-sm font-medium">{e.label}</span>
          </button>
        ))}
      </div>

      <button
        onClick={handleStart}
        disabled={!selected || loading}
        className={`px-8 py-3 rounded-full text-white font-medium text-lg transition-all
          ${selected
            ? 'bg-indigo-500 hover:bg-indigo-600 shadow-lg shadow-indigo-200'
            : 'bg-gray-300 cursor-not-allowed'
          }`}
      >
        {loading ? '正在连接...' : '开始聊天 💬'}
      </button>
    </div>
  )
}
