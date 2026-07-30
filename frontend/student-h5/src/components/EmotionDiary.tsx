import { useState, useEffect } from 'react'
import { api } from '../api'

const EMOTIONS = [
  { label: 'happy', emoji: '😊', text: '开心', color: '#52c41a' },
  { label: 'calm', emoji: '😌', text: '平静', color: '#1677ff' },
  { label: 'neutral', emoji: '😐', text: '一般', color: '#999' },
  { label: 'sad', emoji: '😢', text: '难过', color: '#722ed1' },
  { label: 'angry', emoji: '😠', text: '生气', color: '#ff4d4f' },
  { label: 'anxious', emoji: '😰', text: '焦虑', color: '#fa8c16' },
]

const INTENSITY_LABELS = ['', '很轻微', '比较轻', '中等', '比较强', '非常强']

/** 情绪日记打卡 + 趋势图 */
export default function EmotionDiary({ onBack }) {
  const [today, setToday] = useState(null)
  const [history, setHistory] = useState([])
  const [streak, setStreak] = useState({ streak: 0, total: 0 })
  const [selected, setSelected] = useState(null)
  const [intensity, setIntensity] = useState(3)
  const [note, setNote] = useState('')
  const [submitted, setSubmitted] = useState(false)

  useEffect(() => {
    api('/diary/today').then(d => {
      setToday(d)
      if (d.checkedIn) setSubmitted(true)
    }).catch(() => {})
    api('/diary/history?days=14').then(setHistory).catch(() => {})
    api('/diary/streak').then(setStreak).catch(() => {})
  }, [])

  const submit = async () => {
    if (!selected) return
    await api('/diary/checkin', {
      method: 'POST',
      body: JSON.stringify({ emotionLabel: selected, intensity, note: note || null }),
    })
    setSubmitted(true)
    // 刷新数据
    api('/diary/history?days=14').then(setHistory).catch(() => {})
    api('/diary/streak').then(setStreak).catch(() => {})
  }

  return (
    <div className="min-h-screen flex flex-col items-center p-6 pt-10"
      style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
      <button onClick={onBack} className="self-start text-sm text-gray-400 mb-6">← 返回</button>

      <h2 className="text-xl font-medium text-gray-800 mb-1">情绪日记 📔</h2>
      <p className="text-sm text-gray-400 mb-4">记录每天的心情，看见自己的变化</p>

      {/* 连续打卡 */}
      {streak.streak > 0 && (
        <div className="mb-4 px-4 py-2 bg-white/70 rounded-full text-sm text-gray-500 shadow-sm">
          🔥 已连续打卡 {streak.streak} 天（累计 {streak.total} 天）
        </div>
      )}

      {/* 打卡区域 */}
      {!submitted ? (
        <div className="w-full max-w-sm bg-white rounded-2xl shadow-sm p-6 mb-6">
          <p className="text-sm text-gray-600 mb-3 text-center">今天的心情是？</p>
          <div className="grid grid-cols-3 gap-3 mb-4">
            {EMOTIONS.map(e => (
              <button key={e.label} onClick={() => setSelected(e.label)}
                className={`flex flex-col items-center gap-1 p-3 rounded-xl transition-all
                  ${selected === e.label ? 'ring-2 scale-105' : 'hover:bg-gray-50'}`}
                style={{ background: selected === e.label ? `${e.color}15` : undefined,
                  outline: selected === e.label ? `2px solid ${e.color}` : 'none' }}>
                <span className="text-3xl">{e.emoji}</span>
                <span className="text-xs text-gray-600">{e.text}</span>
              </button>
            ))}
          </div>

          {/* 强度滑块 */}
          <div className="mb-4">
            <div className="flex justify-between text-xs text-gray-400 mb-1">
              <span>感受强度</span>
              <span>{INTENSITY_LABELS[intensity]}</span>
            </div>
            <input type="range" min="1" max="5" value={intensity}
              onChange={e => setIntensity(Number(e.target.value))}
              className="w-full accent-blue-400" />
          </div>

          {/* 备注 */}
          <textarea value={note} onChange={e => setNote(e.target.value)}
            placeholder="想写点什么吗？（可选）"
            className="w-full h-20 p-3 text-sm border border-gray-100 rounded-xl resize-none focus:outline-none focus:ring-1 focus:ring-blue-200" />

          <button onClick={submit} disabled={!selected}
            className="w-full mt-4 py-3 rounded-full text-white text-sm font-medium transition-all disabled:opacity-40"
            style={{ background: 'var(--primary)' }}>
            记录今天 ✨
          </button>
        </div>
      ) : (
        <div className="w-full max-w-sm bg-white rounded-2xl shadow-sm p-6 mb-6 text-center">
          <span className="text-4xl block mb-2">✅</span>
          <p className="text-gray-700">今天已记录，明天再来哦！</p>
        </div>
      )}

      {/* 趋势图（14 天） */}
      {history.length > 0 && (
        <div className="w-full max-w-sm bg-white rounded-2xl shadow-sm p-5">
          <p className="text-sm font-medium text-gray-700 mb-3">近 14 天心情趋势</p>
          <div className="flex items-end justify-between gap-1 h-24">
            {[...history].reverse().map((d, i) => {
              const emo = EMOTIONS.find(e => e.label === d.emotionLabel) || EMOTIONS[2]
              return (
                <div key={i} className="flex flex-col items-center gap-1 flex-1">
                  <div className="w-full rounded-t-md transition-all"
                    style={{ height: `${d.intensity * 18}px`, background: emo.color, opacity: 0.7 }} />
                  <span className="text-[10px]">{emo.emoji}</span>
                </div>
              )
            })}
          </div>
          <div className="flex justify-between mt-2 text-[10px] text-gray-300">
            <span>{history[history.length - 1]?.diaryDate?.slice(5)}</span>
            <span>今天</span>
          </div>
        </div>
      )}
    </div>
  )
}
