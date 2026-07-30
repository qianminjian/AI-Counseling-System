import { useState } from 'react'
import { useTheme } from '../theme/ThemeProvider'
import { api } from '../api'
import { unlockAudio } from '../utils/audioUnlock'
import SessionHistory from './SessionHistory'
import RelaxationExercises from './RelaxationExercises'
import EmotionDiary from './EmotionDiary'
import Achievements from './Achievements'
import SettingsPanel from './SettingsPanel'

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
  const [error, setError] = useState('')
  const [showRelaxation, setShowRelaxation] = useState(false)
  const [showDiary, setShowDiary] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [muted, setMuted] = useState(false)
  const { theme } = useTheme()

  const handleStart = async () => {
    if (!selected) return
    // 关键：在用户手势同步调用栈内立即解锁音频（Safari/Firefox 要求）
    // 不能等 async API 返回后再解锁，否则浏览器不再认为处于"用户激活"状态
    unlockAudio()
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
      setError(e.message || '创建会话失败，请稍后再试')
    } finally {
      setLoading(false)
    }
  }

  if (showRelaxation) {
    return <RelaxationExercises onBack={() => setShowRelaxation(false)} />
  }

  if (showDiary) {
    return <EmotionDiary onBack={() => setShowDiary(false)} />
  }

  return (
    <div className="min-h-screen flex flex-col items-center pt-10 pb-16 px-6 lg:p-10"
      style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))', paddingBottom: 'calc(4rem + env(safe-area-inset-bottom))' }}>
      {/* 设置按钮（右上角） */}
      <button
        onClick={() => setSettingsOpen(true)}
        className="fixed top-4 right-4 z-40 w-10 h-10 flex items-center justify-center rounded-full bg-white/80 shadow-md border border-gray-100 text-gray-500 hover:text-gray-700 active:scale-90 transition-all"
        title="设置"
      >
        ⚙️
      </button>
      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        muted={muted}
        onToggleMute={() => setMuted(v => !v)}
        onToggleWake={() => {}}
      />

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

      {/* 开始按钮 + 放松练习入口 */}
      <div className="flex flex-col items-center gap-4">
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
        {error && (
          <p className="text-sm text-red-500 text-center max-w-xs animate-pulse">{error}</p>
        )}
        <button
          onClick={() => setShowRelaxation(true)}
          className="text-sm text-gray-400 hover:text-gray-600 transition-colors underline underline-offset-4"
        >
          不想聊天？做个放松练习 🌿
        </button>
        <button
          onClick={() => setShowDiary(true)}
          className="text-sm text-gray-400 hover:text-gray-600 transition-colors underline underline-offset-4"
        >
          记录今天的心情 📔
        </button>
      </div>

      {/* 会话历史 */}
      <SessionHistory />

      {/* 成就徽章 */}
      <Achievements />

      {/* 切换用户（共享 Pad 场景） */}
      <button
        onClick={onLogout}
        className="mt-8 px-6 py-2.5 rounded-full border border-gray-200 text-sm text-gray-400 hover:text-gray-600 hover:border-gray-300 transition-all"
      >
        🔄 切换同学
      </button>
    </div>
  )
}
