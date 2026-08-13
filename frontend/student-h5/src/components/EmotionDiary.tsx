import { useState, useEffect } from 'react'
import { api, ApiError } from '../api'
import { fillPath, ENDPOINTS } from '../endpoints'
import { useTheme } from '../theme/ThemeProvider'
import { THEME_STYLES } from '../theme/immersiveStyles'
import BoBoAvatar from './BoBoAvatar'
import SceneDecor from './SceneDecor'
import { emotionLabel, emotionEmoji, STUDENT_EMOTION_TAGS, STUDENT_EMOTION_COLORS } from '../../../shared/src/emotionMeta'

// DOC-082：打卡面板与首页 EmotionSelect 共享 STUDENT_EMOTION_TAGS 基线（5 个情绪统一），
// 色彩单一源 STUDENT_EMOTION_COLORS（与首页 Tailwind 色相一致：黄/蓝/红/紫/橙）
const EMOTIONS = STUDENT_EMOTION_TAGS.map(tag => {
  const c = STUDENT_EMOTION_COLORS[tag]
  return {
    label: tag,
    color: c?.strong ?? '#999',
    textColor: c?.text ?? '#999',
    emoji: emotionEmoji(tag),
    text: emotionLabel(tag),
  }
})

// 趋势图未知/历史码值兜底：显式第一个可选情绪（不依赖数组索引，防列表调整后错位）
const FALLBACK = EMOTIONS[0]

const INTENSITY_LABELS = ['', '很轻微', '比较轻', '中等', '比较强', '非常强']

/** 日记历史条目（/diary/history 返回结构） */
// FE-003：显式类型（此前 useState([]) 推断为 never[] → emotionLabel/intensity/diaryDate 报 TS2339）
interface DiaryHistoryItem {
  emotionLabel: string
  intensity: number
  diaryDate?: string
}

/** 情绪日记打卡 + 趋势图（沉浸式场景主题适配；色板单源 theme/immersiveStyles，FA-02） */
export default function EmotionDiary({ onBack }) {
  const [today, setToday] = useState(null)
  const [history, setHistory] = useState<DiaryHistoryItem[]>([])
  const [streak, setStreak] = useState({ streak: 0, total: 0 })
  const [selected, setSelected] = useState<string | null>(null)
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
    api(fillPath(ENDPOINTS.diaryToday.path, {})).then(d => {
      setToday(d)
      if (d.checkedIn) setSubmitted(true)
    }).catch((e) => { console.warn('[EmotionDiary] 加载今日状态失败:', e); setLoadError(true) })
    api(`${fillPath(ENDPOINTS.diaryHistory.path, {})}?days=14`).then(setHistory).catch((e) => console.warn('[EmotionDiary] 加载趋势失败:', e))
    api(fillPath(ENDPOINTS.diaryStreak.path, {})).then(setStreak).catch((e) => console.warn('[EmotionDiary] 加载连续天数失败:', e))
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
      await api(fillPath(ENDPOINTS.diaryCheckin.path, {}), {
        method: 'POST',
        body: JSON.stringify({ emotionLabel: selected, intensity, note: note || null }),
      })
      setSubmitted(true)
      // 刷新数据
      api(`${fillPath(ENDPOINTS.diaryHistory.path, {})}?days=14`).then(setHistory).catch((e) => console.warn('[EmotionDiary] 刷新趋势失败:', e))
      api(fillPath(ENDPOINTS.diaryStreak.path, {})).then(setStreak).catch((e) => console.warn('[EmotionDiary] 刷新连续天数失败:', e))
    } catch (e) {
      console.error('[EmotionDiary] 打卡失败:', e)
      // BUG-S-08-2（2026-08-12，UI-TEST-012）：按错误类型区分文案——服务端 500 不再是"网络问题"误导
      setSubmitError(
        e instanceof ApiError && e.code === 10001
          ? '打卡失败，请稍后再试'
          : e instanceof ApiError && e.message && e.message !== '请求失败'
            ? `打卡失败：${e.message}`
            : '打卡没成功，请检查网络后再试一次',
      )
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
                  <span className="text-xs font-medium" style={{ color: selected === e.label ? (ts.dark ? '#fff' : e.textColor) : ts.muted }}>{e.text}</span>
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
                const emo = EMOTIONS.find(e => e.label === d.emotionLabel) || FALLBACK
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
