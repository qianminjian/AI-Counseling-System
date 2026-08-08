import { useState, useMemo, useEffect } from 'react'
import { useTheme } from '../theme/ThemeProvider'
import BoBoAvatar from './BoBoAvatar'
import { api, isConsentRequired } from '../api'
import { unlockAudio } from '../utils/audioUnlock'
import { useWakeEnabled } from '../hooks/useWakeEnabled'
import { preloadWakeModel, useWakeModelStatus } from '../hooks/useWakeWord'
import SceneDecor from './SceneDecor'
import RelaxationExercises from './RelaxationExercises'
import EmotionDiary from './EmotionDiary'
import Achievements from './Achievements'
import SettingsPanel from './SettingsPanel'
import ConfirmDialog from './ConfirmDialog'
import { emotionLabel, emotionEmoji } from '../../../shared/src/emotionMeta'

// F4：label/emoji 单一源 shared emotionMeta（与后端 ZH_LABELS 对齐），desc/color 为组件特有展示
const EMOTIONS = [
  { tag: 'happy', desc: '有好事发生', color: 'bg-yellow-100 border-yellow-400 text-yellow-800' },
  { tag: 'sad', desc: '心里不舒服', color: 'bg-blue-100 border-blue-400 text-blue-800' },
  { tag: 'angry', desc: '有点烦躁', color: 'bg-red-100 border-red-400 text-red-800' },
  { tag: 'scared', desc: '有点担心', color: 'bg-purple-100 border-purple-400 text-purple-800' },
  { tag: 'nervous', desc: '心跳加速', color: 'bg-orange-100 border-orange-400 text-orange-800' },
].map(e => ({
  ...e,
  label: emotionLabel(e.tag),
  emoji: emotionEmoji(e.tag),
}))

export default function EmotionSelect({ onStart, userName, onLogout, onConsentRequired }) {
  const [selected, setSelected] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [showRelaxation, setShowRelaxation] = useState(false)
  const [showDiary, setShowDiary] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [muted, setMuted] = useState(false)
  const [confirmSwitch, setConfirmSwitch] = useState(false)
  // A4 收敛（ARCH-006）：唤醒开关统一由 useWakeEnabled 管理（初始化 + 切换 + 持久化 + 失败安全）
  const { enabled: wakeEnabled, setEnabled: setWakeEnabled } = useWakeEnabled()
  const { theme, themeId } = useTheme()

  // 麦克风环境检测（传给设置面板，避免误显"不支持"）
  const micSupported = useMemo(
    () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia,
    []
  )

  // 预加载唤醒模型：情绪选择页停留时间足够下载模型，进对话时直接就绪
  useEffect(() => {
    if (micSupported) preloadWakeModel()
  }, [micSupported])

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
      // 监护人同意门禁拦截（CONSENT_REQUIRED 20003）→ 切换至验证码闭环页（AUTH-040）
      if (isConsentRequired(e)) {
        onConsentRequired?.()
        return
      }
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
    <div className={`emotion-scene emotion-scene--${themeId} flex flex-col items-center pt-10 pb-16 px-6 lg:p-10`}
      style={{ paddingBottom: 'calc(4rem + env(safe-area-inset-bottom))' }}>
      {/* 三主题场景装饰（与登录页同款） */}
      <SceneDecor themeId={themeId} />

      {/* 顶栏操作区（右上角）：切换同学 + 设置 */}
      <div className="fixed top-4 right-4 z-40 flex items-center gap-2">
        <button
          onClick={() => setConfirmSwitch(true)}
          className="h-10 flex items-center gap-1.5 px-3 rounded-full bg-orange-50 border border-orange-200 text-orange-600 hover:bg-orange-100 active:scale-90 transition-all shadow-md"
          title="切换同学"
        >
          <span className="text-base">🔄</span>
          <span className="text-xs font-semibold">换人</span>
        </button>
        <button
          onClick={() => setSettingsOpen(true)}
          className="w-10 h-10 flex items-center justify-center rounded-full bg-white/80 shadow-md border border-gray-100 text-gray-500 hover:text-gray-700 active:scale-90 transition-all"
          title="设置"
        >
          ⚙️
        </button>
      </div>
      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        muted={muted}
        onToggleMute={() => setMuted(v => !v)}
        wakeSupported={micSupported}
        wakeOn={wakeEnabled}
        onToggleWake={() => setWakeEnabled(!wakeEnabled)}
      />
      {/* 切换同学二次确认 */}
      <ConfirmDialog
        open={confirmSwitch}
        emoji="👋"
        title="要退出让别的同学用吗？"
        message="退出后需要重新登录哦"
        confirmText="确认退出"
        danger
        onConfirm={() => { setConfirmSwitch(false); onLogout?.() }}
        onCancel={() => setConfirmSwitch(false)}
      />

      {/* 标题区：儿童化圆体标题 + 伙伴漂浮动画 */}
      <div className="relative z-10 mb-4 lg:mb-6 float-companion"><BoBoAvatar size={72} colors={theme.bobo} /></div>
      <h1 className={`relative z-10 kid-title text-2xl lg:text-4xl mb-2 emotion-title--${themeId}`}>
        嗨，{userName || '同学'}！
      </h1>
      <p className={`relative z-10 lg:text-xl mb-8 lg:mb-12 emotion-sub--${themeId}`}>今天你的心情怎么样呀？</p>

      {/* 情绪选择：手机 3+2 网格 / Pad 横排大卡片（选中态保留情绪本色） */}
      <div className="relative z-10 grid grid-cols-3 lg:flex lg:gap-6 gap-4 mb-8 lg:mb-12 max-w-sm lg:max-w-none w-full lg:w-auto">
        {EMOTIONS.map((e) => (
          <button
            key={e.tag}
            onClick={() => setSelected(e.tag)}
            className={`flex flex-col items-center gap-2 lg:gap-3 p-4 lg:p-8 lg:w-44 rounded-2xl lg:rounded-3xl border-2 transition-all
              ${selected === e.tag
                ? e.color + ' scale-105 shadow-lg'
                : 'bg-white/90 border-transparent shadow-md hover:shadow-lg active:scale-95'
              }`}
          >
            <span className="text-3xl lg:text-6xl">{e.emoji}</span>
            <span className="text-sm lg:text-xl font-medium">{e.label}</span>
            <span className="hidden lg:block text-sm text-gray-400">{e.desc}</span>
          </button>
        ))}
      </div>

      {/* 开始按钮 + 放松练习入口 */}
      <div className="relative z-10 flex flex-col items-center gap-4 w-full max-w-sm lg:max-w-md">
        <button
          onClick={handleStart}
          disabled={!selected || loading}
          className={`w-full px-10 lg:px-16 py-4 lg:py-5 rounded-full text-white font-medium text-lg lg:text-2xl transition-all
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
        {/* 底部功能入口（卡片式，深色/浅色主题都清晰可辨） */}
        <div className="flex gap-3 w-full mt-2">
          <button
            onClick={() => setShowRelaxation(true)}
            className={`flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-2xl backdrop-blur-sm shadow-sm transition-all active:scale-95
              ${themeId === 'garden'
                ? 'bg-white/70 border border-pink-200 hover:bg-white/90'
                : 'bg-white/20 border border-white/30 hover:bg-white/30'
              }`}
          >
            <span className="text-lg">🌿</span>
            <span className={`text-sm font-medium drop-shadow-sm ${themeId === 'garden' ? 'text-pink-700' : 'text-white'}`}>放松练习</span>
          </button>
          <button
            onClick={() => setShowDiary(true)}
            className={`flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-2xl backdrop-blur-sm shadow-sm transition-all active:scale-95
              ${themeId === 'garden'
                ? 'bg-white/70 border border-pink-200 hover:bg-white/90'
                : 'bg-white/20 border border-white/30 hover:bg-white/30'
              }`}
          >
            <span className="text-lg">📔</span>
            <span className={`text-sm font-medium drop-shadow-sm ${themeId === 'garden' ? 'text-pink-700' : 'text-white'}`}>心情日记</span>
          </button>
        </div>
      </div>

      {/* 成就徽章 */}
      <div className="relative z-10 w-full max-w-sm lg:max-w-md flex flex-col items-center mt-4">
        <Achievements />
      </div>

      {/* 语音模型加载状态（底部微妙提示，让孩子/家长知道语音功能是否就绪） */}
      {micSupported && <WakeModelStatusPill />}
    </div>
  )
}

/** 语音模型加载状态胶囊（底部微妙提示） */
function WakeModelStatusPill() {
  const { status, progress } = useWakeModelStatus()
  if (status === 'idle') return null // 未触发加载（如未开启语音）
  const map = {
    loading: { text: `🎧 语音耳朵准备中 ${progress > 0 ? progress + '%' : '…'}`, cls: 'bg-amber-50 text-amber-600 border-amber-200 animate-pulse' },
    ready:   { text: '🎧 语音耳朵已就绪', cls: 'bg-green-50 text-green-600 border-green-200' },
    error:   { text: '⚠️ 语音加载失败，进对话后重试', cls: 'bg-red-50 text-red-500 border-red-200' },
  }[status]
  if (!map) return null
  return (
    <div className={`relative z-10 mt-4 px-4 py-1.5 rounded-full border text-xs font-medium ${map.cls}`}>
      {map.text}
    </div>
  )
}
