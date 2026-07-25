import { useState } from 'react'
import { useTheme } from '../theme/ThemeProvider'
import { api } from '../api'

const EMOTIONS = [
  { tag: 'happy', emoji: '😊', label: '开心', desc: '有好事发生', color: 'bg-yellow-100 border-yellow-400 text-yellow-800' },
  { tag: 'sad', emoji: '😢', label: '难过', desc: '心里不舒服', color: 'bg-blue-100 border-blue-400 text-blue-800' },
  { tag: 'angry', emoji: '😠', label: '生气', desc: '有点烦躁', color: 'bg-red-100 border-red-400 text-red-800' },
  { tag: 'scared', emoji: '😨', label: '害怕', desc: '有点担心', color: 'bg-purple-100 border-purple-400 text-purple-800' },
  { tag: 'nervous', emoji: '😰', label: '紧张', desc: '心跳加速', color: 'bg-orange-100 border-orange-400 text-orange-800' },
]

export default function EmotionSelect({ onStart, userName, onLogout }) {
  const [selected, setSelected] = useState(null)
  const [loading, setLoading] = useState(false)
  const { theme } = useTheme()

  const handleStart = async () => {
    if (!selected) return
    setLoading(true)
    try {
      const data = await api('/chat/sessions', {
        method: 'POST',
        body: JSON.stringify({ emotionTag: selected, channel: 'h5' }),
      })
      onStart({
        sessionId: data.sessionId,
        greeting: data.greeting,
        emotionTag: selected,
      })
    } catch (e) {
      console.error('创建会话失败', e)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 lg:p-10"
      style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
      {/* 标题区：儿童化圆体标题 + 伙伴漂浮动画 */}
      <div className="text-6xl lg:text-8xl mb-4 lg:mb-6 float-companion">{theme.companion}</div>
      <h1 className="kid-title text-2xl lg:text-4xl text-gray-800 mb-2">
        嗨，{userName || '同学'}！
      </h1>
      <p className="text-gray-500 lg:text-xl mb-8 lg:mb-12">今天你的心情怎么样呀？</p>

      {/* 情绪选择：手机 3+2 网格 / Pad 横排大卡片 */}
      <div className="grid grid-cols-3 lg:flex lg:gap-6 gap-4 mb-8 lg:mb-12 max-w-sm lg:max-w-none w-full lg:w-auto">
        {EMOTIONS.map((e) => (
          <button
            key={e.tag}
            onClick={() => setSelected(e.tag)}
            className={`flex flex-col items-center gap-2 lg:gap-3 p-4 lg:p-8 lg:w-44 rounded-2xl lg:rounded-3xl border-2 transition-all
              ${selected === e.tag
                ? e.color + ' scale-105 shadow-lg'
                : 'bg-white border-gray-200 hover:border-gray-300 active:scale-95'
              }`}
          >
            <span className="text-3xl lg:text-6xl">{e.emoji}</span>
            <span className="text-sm lg:text-xl font-medium">{e.label}</span>
            <span className="hidden lg:block text-sm text-gray-400">{e.desc}</span>
          </button>
        ))}
      </div>

      {/* 开始按钮：触摸目标 ≥ 64px */}
      <button
        onClick={handleStart}
        disabled={!selected || loading}
        className={`px-10 lg:px-16 py-4 lg:py-5 rounded-full text-white font-medium text-lg lg:text-2xl transition-all
          ${selected
            ? 'active:scale-95 shadow-lg'
            : 'bg-gray-300 cursor-not-allowed'
          }`}
        style={selected ? { background: 'var(--primary)' } : undefined}
      >
        {loading ? '正在连接...' : '开始聊天 💬'}
      </button>
    </div>
  )
}
