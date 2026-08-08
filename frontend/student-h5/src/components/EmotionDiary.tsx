import { useState, useEffect } from 'react'
import { api } from '../api'
import { useTheme } from '../theme/ThemeProvider'
import BoBoAvatar from './BoBoAvatar'
import SceneDecor from './SceneDecor'
import { emotionLabel, emotionEmoji } from '../../../shared/src/emotionMeta'

// F4：emoji/text 单一源 shared emotionMeta（neutral→平静、anxious→紧张 对齐 DC-008），color 为组件特有展示
const EMOTIONS = [
  { label: 'happy', color: '#52c41a' },
  { label: 'calm', color: '#1677ff' },
  { label: 'neutral', color: '#999' },
  { label: 'sad', color: '#722ed1' },
  { label: 'angry', color: '#ff4d4f' },
  { label: 'anxious', color: '#fa8c16' },
].map(e => ({
  ...e,
  emoji: emotionEmoji(e.label),
  text: emotionLabel(e.label),
}))

const INTENSITY_LABELS = ['', '很轻微', '比较轻', '中等', '比较强', '非常强']

/**
 * 主题适配样式映射
 * 与登录页 / 情绪选择页同款沉浸式场景：
 * - ocean / rainbow 为深色场景 → 浅色文字 + 玻璃拟态卡片
 * - garden 为浅色场景 → 深色文字 + 白底卡片
 */
const THEME_STYLES = {
  ocean: {
    dark: true,
    title: '#ffffff',
    titleShadow: '0 2px 12px rgba(0,0,0,0.25)',
    sub: 'rgba(224,242,254,0.78)',
    text: 'rgba(240,249,255,0.94)',
    muted: 'rgba(186,230,253,0.62)',
    back: '#7dd3fc',
    cardBg: 'rgba(255,255,255,0.10)',
    cardBorder: '1px solid rgba(255,255,255,0.18)',
    cardShadow: '0 8px 32px rgba(2,132,199,0.25)',
    pillBg: 'rgba(125,211,252,0.16)',
    pillBorder: '1px solid rgba(125,211,252,0.32)',
    pillText: '#bae6fd',
    inputBg: 'rgba(255,255,255,0.12)',
    inputBorder: 'rgba(255,255,255,0.24)',
    inputFocus: 'rgba(125,211,252,0.65)',
    glow: 'rgba(56,189,248,0.55)',
    btnBg: 'linear-gradient(135deg, #0ea5e9, #06b6d4)',
  },
  garden: {
    dark: false,
    title: '#9d174d',
    titleShadow: 'none',
    sub: 'rgba(157,23,77,0.62)',
    text: '#831843',
    muted: 'rgba(190,24,93,0.55)',
    back: '#db2777',
    cardBg: 'rgba(255,255,255,0.82)',
    cardBorder: '1px solid rgba(244,114,182,0.28)',
    cardShadow: '0 8px 28px rgba(236,72,153,0.14)',
    pillBg: 'rgba(244,114,182,0.12)',
    pillBorder: '1px solid rgba(244,114,182,0.32)',
    pillText: '#be185d',
    inputBg: 'rgba(255,255,255,0.65)',
    inputBorder: 'rgba(244,114,182,0.32)',
    inputFocus: 'rgba(236,72,153,0.6)',
    glow: 'rgba(236,72,153,0.4)',
    btnBg: 'linear-gradient(135deg, #ec4899, #a855f7)',
  },
  rainbow: {
    dark: true,
    title: '#e0e7ff',
    titleShadow: '0 0 18px rgba(139,92,246,0.4)',
    sub: 'rgba(165,180,252,0.82)',
    text: 'rgba(224,231,255,0.94)',
    muted: 'rgba(129,140,248,0.72)',
    back: '#a5b4fc',
    cardBg: 'rgba(139,92,246,0.12)',
    cardBorder: '1px solid rgba(139,92,246,0.30)',
    cardShadow: '0 8px 32px rgba(76,29,149,0.35)',
    pillBg: 'rgba(139,92,246,0.18)',
    pillBorder: '1px solid rgba(167,139,250,0.38)',
    pillText: '#c4b5fd',
    inputBg: 'rgba(139,92,246,0.14)',
    inputBorder: 'rgba(139,92,246,0.38)',
    inputFocus: 'rgba(167,139,250,0.75)',
    glow: 'rgba(139,92,246,0.55)',
    btnBg: 'linear-gradient(135deg, #8b5cf6, #6d28d9)',
  },
} as const

/** 情绪日记打卡 + 趋势图（沉浸式场景主题适配） */
export default function EmotionDiary({ onBack }) {
  const [today, setToday] = useState(null)
  const [history, setHistory] = useState([])
  const [streak, setStreak] = useState({ streak: 0, total: 0 })
  const [selected, setSelected] = useState(null)
  const [intensity, setIntensity] = useState(3)
  const [note, setNote] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [submitting, setSubmitting] = useState(false) // AUD-020：打卡提交中防重复点击
  const [loadError, setLoadError] = useState(false)   // AUD-020：初始数据加载失败（可重试）
  const [submitError, setSubmitError] = useState('')  // AUD-020：打卡失败提示
  const { theme, themeId } = useTheme()
  const ts = THEME_STYLES[themeId] || THEME_STYLES.ocean

  // AUD-020：初始加载不再 .catch(() => {}) 静默吞错——today 失败置错误态供重试，
  // history/streak 失败仅 warn（趋势图缺省不影响打卡主流程）
  const loadData = () => {
    setLoadError(false)
    api('/diary/today').then(d => {
      setToday(d)
      if (d.checkedIn) setSubmitted(true)
    }).catch((e) => { console.warn('[EmotionDiary] 加载今日状态失败:', e); setLoadError(true) })
    api('/diary/history?days=14').then(setHistory).catch((e) => console.warn('[EmotionDiary] 加载趋势失败:', e))
    api('/diary/streak').then(setStreak).catch((e) => console.warn('[EmotionDiary] 加载连续天数失败:', e))
  }

  useEffect(() => {
    loadData()
  }, [])

  // AUD-020：打卡失败不 unhandled rejection——try/catch + 错误提示 + loading
  const submit = async () => {
    if (!selected || submitting) return
    setSubmitting(true)
    setSubmitError('')
    try {
      await api('/diary/checkin', {
        method: 'POST',
        body: JSON.stringify({ emotionLabel: selected, intensity, note: note || null }),
      })
      setSubmitted(true)
      // 刷新数据
      api('/diary/history?days=14').then(setHistory).catch((e) => console.warn('[EmotionDiary] 刷新趋势失败:', e))
      api('/diary/streak').then(setStreak).catch((e) => console.warn('[EmotionDiary] 刷新连续天数失败:', e))
    } catch (e) {
      console.error('[EmotionDiary] 打卡失败:', e)
      setSubmitError('打卡没成功，请检查网络后再试一次')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={`emotion-scene emotion-scene--${themeId} flex flex-col items-center px-6 pt-10 pb-16`}>
      <SceneDecor themeId={themeId} />

      <button onClick={onBack}
        className="self-start relative z-10 flex items-center gap-1 text-sm font-medium mb-6 transition-opacity hover:opacity-100 opacity-75"
        style={{ color: ts.back }}>
        ← 返回
      </button>

      <div className="relative z-10 flex flex-col items-center w-full max-w-sm">
        {/* 头部：伙伴 + 标题 */}
        <div className="mb-3 float-companion"><BoBoAvatar size={48} colors={theme.bobo} /></div>
        <h2 className="kid-title text-2xl font-bold mb-1" style={{ color: ts.title, textShadow: ts.titleShadow }}>情绪日记 📔</h2>
        <p className="text-sm mb-5 text-center" style={{ color: ts.sub }}>记录每天的心情，看见自己的变化</p>

        {/* 连续打卡 */}
        {streak.streak > 0 && (
          <div className="mb-5 px-5 py-2.5 rounded-full text-sm font-medium backdrop-blur-sm"
            style={{ background: ts.pillBg, border: ts.pillBorder, color: ts.pillText, boxShadow: `0 4px 16px ${ts.glow}` }}>
            🔥 已连续打卡 {streak.streak} 天（累计 {streak.total} 天）
          </div>
        )}

        {/* 打卡区域 */}
        {loadError ? (
          <div className="w-full rounded-3xl p-6 mb-6 text-center backdrop-blur-md"
            style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}>
            <p className="font-semibold mb-3" style={{ color: ts.title }}>今天的状态没能加载出来</p>
            <button onClick={loadData}
              className="px-5 py-2 rounded-full text-white text-sm font-semibold transition-all active:scale-95"
              style={{ background: ts.btnBg }}>
              再试一次
            </button>
          </div>
        ) : !submitted ? (
          <div className="w-full rounded-3xl p-6 mb-6 backdrop-blur-md"
            style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}>
            <p className="text-sm font-semibold mb-4 text-center" style={{ color: ts.text }}>今天的心情是？</p>
            <div className="grid grid-cols-3 gap-3 mb-5">
              {EMOTIONS.map(e => (
                <button key={e.label} onClick={() => setSelected(e.label)}
                  className="flex flex-col items-center gap-1.5 p-3 rounded-2xl transition-all active:scale-95"
                  style={{
                    background: selected === e.label ? `${e.color}26` : ts.dark ? 'rgba(255,255,255,0.06)' : 'rgba(255,255,255,0.5)',
                    border: selected === e.label ? `2px solid ${e.color}` : `2px solid ${ts.dark ? 'rgba(255,255,255,0.10)' : 'rgba(0,0,0,0.04)'}`,
                    transform: selected === e.label ? 'scale(1.06)' : undefined,
                    boxShadow: selected === e.label ? `0 6px 16px ${e.color}40` : undefined,
                  }}>
                  <span className="text-3xl">{e.emoji}</span>
                  <span className="text-xs font-medium" style={{ color: selected === e.label ? (ts.dark ? '#fff' : e.color) : ts.muted }}>{e.text}</span>
                </button>
              ))}
            </div>

            {/* 强度滑块 */}
            <div className="mb-5">
              <div className="flex justify-between text-xs mb-2" style={{ color: ts.muted }}>
                <span className="font-medium">感受强度</span>
                <span className="font-semibold" style={{ color: ts.pillText }}>{INTENSITY_LABELS[intensity]}</span>
              </div>
              <input type="range" min="1" max="5" value={intensity}
                onChange={e => setIntensity(Number(e.target.value))}
                className="w-full h-2 rounded-full appearance-none cursor-pointer"
                style={{ accentColor: 'var(--primary)', background: ts.pillBg }} />
            </div>

            {/* 备注 */}
            <textarea value={note} onChange={e => setNote(e.target.value)}
              placeholder="想写点什么吗？（可选）"
              className="w-full h-20 p-3 text-sm rounded-2xl resize-none transition-all focus:outline-none backdrop-blur-sm"
              style={{
                border: `1.5px solid ${ts.inputBorder}`,
                background: ts.inputBg,
                color: ts.text,
              }}
              onFocus={e => e.target.style.borderColor = ts.inputFocus}
              onBlur={e => e.target.style.borderColor = ts.inputBorder} />

            <button onClick={submit} disabled={!selected || submitting}
              className="w-full mt-4 py-3.5 rounded-full text-white text-sm font-semibold transition-all active:scale-95 disabled:opacity-40 disabled:shadow-none"
              style={{ background: ts.btnBg, boxShadow: selected ? `0 10px 30px ${ts.glow}` : undefined }}>
              {submitting ? '正在记录…' : '记录今天 ✨'}
            </button>
            {submitError && (
              <p className="mt-3 text-xs text-center" style={{ color: ts.back }}>{submitError}</p>
            )}
          </div>
        ) : (
          <div className="w-full rounded-3xl p-8 mb-6 text-center backdrop-blur-md"
            style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}>
            <style>{`@keyframes diary-pop{0%{transform:scale(0.4);opacity:0}60%{transform:scale(1.15)}100%{transform:scale(1);opacity:1}}`}</style>
            <span className="text-5xl block mb-3" style={{ animation: 'diary-pop 0.5s ease-out' }}>✅</span>
            <p className="font-semibold" style={{ color: ts.title, textShadow: ts.titleShadow }}>今天已记录，明天再来哦！</p>
          </div>
        )}

        {/* 趋势图（14 天） */}
        {history.length > 0 && (
          <div className="w-full rounded-3xl p-5 backdrop-blur-md"
            style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}>
            <p className="text-sm font-semibold mb-4" style={{ color: ts.title }}>近 14 天心情趋势</p>
            <div className="flex items-end justify-between gap-1 h-28">
              {[...history].reverse().map((d, i) => {
                const emo = EMOTIONS.find(e => e.label === d.emotionLabel) || EMOTIONS[2]
                return (
                  <div key={i} className="flex flex-col items-center gap-1 flex-1">
                    <div className="w-full rounded-t-lg transition-all hover:opacity-100 opacity-85"
                      style={{ height: `${d.intensity * 20}px`, background: emo.color, boxShadow: `0 2px 8px ${emo.color}40` }} />
                    <span className="text-[10px]">{emo.emoji}</span>
                  </div>
                )
              })}
            </div>
            <div className="flex justify-between mt-2 text-[10px]" style={{ color: ts.muted }}>
              <span>{history[history.length - 1]?.diaryDate?.slice(5)}</span>
              <span>今天</span>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
