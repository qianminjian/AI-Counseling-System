import { useState, useEffect, useRef } from 'react'
import { api } from '../api'
import { useTheme } from '../theme/ThemeProvider'
import { THEME_STYLES } from '../theme/immersiveStyles'
import BoBoAvatar from './BoBoAvatar'
import { readLocalStorageSafe, writeLocalStorageSafe } from '../utils/storage'
import { browserSpeak, stopBrowserSpeak } from '../utils/browserSpeak'
import SceneDecor from './SceneDecor'

const CATEGORY_EMOJI = {
  breathing: '🌬️',
  mindfulness: '🧘',
  visualization: '🌈',
  somatic: '🦋',
}

/* 各练习类别的柔色芯片（半透明，深浅场景通用） */
const CATEGORY_ACCENT = {
  breathing: 'rgba(56,189,248,0.22)',
  mindfulness: 'rgba(167,139,250,0.22)',
  visualization: 'rgba(251,191,36,0.24)',
  somatic: 'rgba(52,211,153,0.22)',
}

const VOICE_PREF_KEY = 'mindsafe_relax_voice_on'

/** 练习数据结构（FE-003：与 api 返回对齐，仅约束组件内消费面） */
interface RelaxationExercise {
  id: string
  name: string
  description: string
  category: 'breathing' | 'mindfulness' | 'visualization' | 'somatic'
  durationSeconds: number
}

/** 放松练习语音引导朗读（轻柔语速，冥想/呼吸场景；F5：复用共享 browserSpeak 降级链） */
function speakGuide(text) {
  // bobo 人设（温柔女老师，pitch 1.05 × rateScale 0.95），rate 0.9 → 实际语速 ~0.86
  browserSpeak(text, { rate: 0.9, persona: 'bobo' })
}

function stopGuide() {
  stopBrowserSpeak()
}

/** 呼吸引导动画圆圈（主题色驱动 + 光晕脉冲） */
function BreathingCircle({ phase, seconds, ts }) {
  const phaseConfig = {
    inhale: { label: '吸气', anim: 'breathe-in' },
    hold: { label: '屏住', anim: 'breathe-hold' },
    exhale: { label: '呼气', anim: 'breathe-out' },
  }
  const cfg = phaseConfig[phase] || phaseConfig.inhale

  return (
    <div className="flex flex-col items-center gap-4">
      <style>{`
        @keyframes breathe-in { from { transform: scale(0.8); } to { transform: scale(1.3); } }
        @keyframes breathe-hold { from { transform: scale(1.3); } to { transform: scale(1.3); } }
        @keyframes breathe-out { from { transform: scale(1.3); } to { transform: scale(0.8); } }
        @keyframes pulse-ring { 0% { box-shadow: 0 0 0 0 var(--glow); } 100% { box-shadow: 0 0 0 30px transparent; } }
      `}</style>
      <div
        className="w-44 h-44 rounded-full flex items-center justify-center"
        style={{
          ['--glow' as any]: ts.glow,
          animation: `${cfg.anim} ${seconds}s ease-in-out forwards, pulse-ring 2.5s infinite`,
          background: `radial-gradient(circle at 32% 28%, ${ts.circleFrom}, ${ts.circleTo})`,
          boxShadow: `0 12px 40px ${ts.glow}, inset 0 3px 12px rgba(255,255,255,0.35)`,
        }}
      >
        <span className="text-white text-xl font-semibold drop-shadow-md">{cfg.label}</span>
      </div>
      <span className="text-4xl font-light tabular-nums" style={{ color: ts.title, textShadow: ts.titleShadow }}>{seconds}</span>
    </div>
  )
}

/** 单个练习执行器 */
function ExerciseRunner({ exercise, onComplete, onBack, ts }) {
  const [phase, setPhase] = useState('inhale')
  const [seconds, setSeconds] = useState(0)
  const [elapsed, setElapsed] = useState(0)
  const [done, setDone] = useState(false)
  const [voiceOn, setVoiceOn] = useState(() => readLocalStorageSafe(VOICE_PREF_KEY, '') !== '0')
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const voiceOnRef = useRef(voiceOn)
  voiceOnRef.current = voiceOn

  const isBreathing = exercise.category === 'breathing'

  // 呼吸节奏：3-2-3 或 4-7-8
  const pattern = exercise.id === 'breathing_478'
    ? { inhale: 4, hold: 7, exhale: 8 }
    : { inhale: 3, hold: 2, exhale: 3 }

  useEffect(() => {
    let currentPhase = 'inhale'
    let count = pattern.inhale
    setPhase(currentPhase)
    setSeconds(count)

    timerRef.current = setInterval(() => {
      count--
      setElapsed((e) => e + 1)
      if (count <= 0) {
        if (currentPhase === 'inhale') { currentPhase = 'hold'; count = pattern.hold }
        else if (currentPhase === 'hold') { currentPhase = 'exhale'; count = pattern.exhale }
        else { currentPhase = 'inhale'; count = pattern.inhale }
        setPhase(currentPhase)
      }
      setSeconds(count)
    }, 1000)

    return () => { if (timerRef.current) clearInterval(timerRef.current) }
  }, [])

  // 语音引导：呼吸类按相位提示，其他类开始时朗读引导词
  useEffect(() => {
    if (!voiceOnRef.current || done) return
    if (isBreathing) {
      const phaseWords = { inhale: '慢慢吸气', hold: '屏住呼吸', exhale: '轻轻呼气' }
      speakGuide(phaseWords[phase] || '')
    }
  }, [phase, isBreathing, done])

  useEffect(() => {
    if (!isBreathing && voiceOnRef.current) {
      speakGuide(exercise.description)
    }
    return () => stopGuide()
  }, [])

  useEffect(() => {
    if (elapsed >= exercise.durationSeconds && !done) {
      setDone(true)
      if (timerRef.current) clearInterval(timerRef.current)
      if (voiceOnRef.current) speakGuide('做得好！感觉放松一些了吗？')
      // 记录完成
      api('/relaxation/sessions', {
        method: 'POST',
        body: JSON.stringify({
          exerciseType: exercise.id,
          durationSeconds: elapsed,
          completed: true,
        }),
      }).catch(() => {})
    }
  }, [elapsed, exercise, done])

  // ===== 完成界面 =====
  if (done) {
    return (
      <div className="relative z-10 flex flex-col items-center gap-6 py-12 w-full max-w-sm">
        <style>{`@keyframes pop-in{0%{transform:scale(0.4);opacity:0}60%{transform:scale(1.15)}100%{transform:scale(1);opacity:1}}
          @keyframes spark{0%,100%{transform:translateY(0) scale(1);opacity:.9}50%{transform:translateY(-10px) scale(1.25);opacity:.5}}`}</style>
        <div className="relative flex items-center justify-center">
          <span className="text-7xl" style={{ animation: 'pop-in 0.5s ease-out' }}>🎉</span>
          <span className="absolute -top-3 -left-6 text-2xl" style={{ animation: 'spark 1.8s ease-in-out infinite' }}>✨</span>
          <span className="absolute -top-1 -right-7 text-xl" style={{ animation: 'spark 1.8s ease-in-out infinite 0.5s' }}>🌟</span>
        </div>
        <div className="text-center px-2">
          <p className="text-xl font-bold mb-1.5" style={{ color: ts.title, textShadow: ts.titleShadow }}>做得好！感觉放松一些了吗？</p>
          <p className="text-sm" style={{ color: ts.sub }}>坚持练习，心情会越来越平静哦</p>
        </div>
        <button
          onClick={onComplete}
          className="px-12 py-3.5 rounded-full text-white text-sm font-semibold shadow-lg active:scale-95 transition-all"
          style={{ background: ts.btnBg, boxShadow: `0 10px 30px ${ts.glow}` }}
        >
          完成
        </button>
      </div>
    )
  }

  // ===== 执行界面 =====
  return (
    <div className="relative z-10 flex flex-col items-center gap-6 py-4 w-full max-w-sm">
      <div className="flex items-center gap-2.5">
        <span className="text-2xl">{CATEGORY_EMOJI[exercise.category]}</span>
        <h3 className="text-xl font-bold" style={{ color: ts.title, textShadow: ts.titleShadow }}>{exercise.name}</h3>
      </div>

      {/* 语音引导开关 */}
      <button
        onClick={() => {
          const next = !voiceOn
          setVoiceOn(next)
          writeLocalStorageSafe(VOICE_PREF_KEY, next ? '1' : '0')
          if (!next) stopGuide()
        }}
        className="text-xs px-4 py-1.5 rounded-full transition-all active:scale-95 backdrop-blur-sm"
        style={{ background: ts.pillBg, border: ts.pillBorder, color: ts.pillText }}
      >
        {voiceOn ? '🔊 语音引导：开' : '🔇 语音引导：关'}
      </button>

      {isBreathing ? (
        <BreathingCircle phase={phase} seconds={seconds} ts={ts} />
      ) : (
        <div className="text-center px-7 py-10 rounded-3xl w-full backdrop-blur-md"
          style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}>
          <style>{`@keyframes gentle-bob{0%,100%{transform:translateY(0)}50%{transform:translateY(-8px)}}`}</style>
          <span className="text-6xl block mb-5" style={{ animation: 'gentle-bob 3.5s ease-in-out infinite' }}>
            {CATEGORY_EMOJI[exercise.category]}
          </span>
          <p className="leading-relaxed text-base" style={{ color: ts.text }}>{exercise.description}</p>
        </div>
      )}

      {/* 进度条 */}
      <div className="w-full px-2">
        <div className="h-2.5 rounded-full overflow-hidden" style={{ background: ts.progressTrack }}>
          <div
            className="h-full rounded-full transition-all duration-1000"
            style={{
              width: `${Math.min((elapsed / exercise.durationSeconds) * 100, 100)}%`,
              background: `linear-gradient(90deg, ${ts.circleFrom}, ${ts.circleTo})`,
            }}
          />
        </div>
        <div className="flex justify-between mt-1.5 text-xs tabular-nums" style={{ color: ts.muted }}>
          <span>{elapsed}s</span>
          <span>{exercise.durationSeconds}s</span>
        </div>
      </div>

      <button onClick={() => { stopGuide(); onBack() }}
        className="text-sm underline underline-offset-4 opacity-70 hover:opacity-100 transition-opacity"
        style={{ color: ts.muted }}>
        提前结束
      </button>
    </div>
  )
}

/** 今日练习计数 */
function TodayCounter({ ts }) {
  const [count, setCount] = useState(0)
  useEffect(() => {
    api('/relaxation/sessions/today').then(d => setCount(d.count || 0)).catch(() => {})
  }, [])
  if (count === 0) return null
  return (
    <div className="mb-6 px-5 py-2.5 rounded-full text-sm font-medium backdrop-blur-sm"
      style={{ background: ts.pillBg, border: ts.pillBorder, color: ts.pillText, boxShadow: `0 4px 16px ${ts.glow}` }}>
      ✨ 今天已完成 {count} 次练习，继续加油！
    </div>
  )
}

/**
 * 放松练习面板（从 EmotionSelect 页面进入）
 * 全面主题适配：复用登录页 / 情绪选择页同款沉浸式场景（ocean 海底 / garden 糖果 / rainbow 星空），
 * 列表 / 呼吸执行 / 正念执行 / 完成四个页面状态均随主题联动。
 */
export default function RelaxationExercises({ onBack }) {
  const [exercises, setExercises] = useState<RelaxationExercise[]>([])
  const [active, setActive] = useState<RelaxationExercise | null>(null)
  const [loading, setLoading] = useState(true)
  const { theme, themeId } = useTheme()
  const ts = THEME_STYLES[themeId] || THEME_STYLES.ocean

  useEffect(() => {
    api('/relaxation/exercises')
      .then(setExercises)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  // ===== 练习执行 / 完成页 =====
  if (active) {
    return (
      <div className={`emotion-scene emotion-scene--${themeId} flex flex-col items-center justify-center px-6 py-10`}>
        <SceneDecor themeId={themeId} />
        <button onClick={() => setActive(null)}
          className="absolute top-6 left-6 z-20 flex items-center gap-1 text-sm font-medium transition-opacity hover:opacity-100 opacity-75"
          style={{ color: ts.back }}>
          ← 返回
        </button>
        <ExerciseRunner exercise={active} onComplete={() => setActive(null)} onBack={() => setActive(null)} ts={ts} />
      </div>
    )
  }

  // ===== 练习列表页 =====
  return (
    <div className={`emotion-scene emotion-scene--${themeId} flex flex-col items-center px-6 pt-12 pb-16`}>
      <SceneDecor themeId={themeId} />

      <button onClick={onBack}
        className="self-start relative z-10 flex items-center gap-1 text-sm font-medium mb-6 transition-opacity hover:opacity-100 opacity-75"
        style={{ color: ts.back }}>
        ← 返回
      </button>

      <div className="relative z-10 flex flex-col items-center w-full max-w-sm">
        {/* 头部：伙伴 + 标题 */}
        <div className="mb-3 float-companion"><BoBoAvatar size={48} colors={theme.bobo} /></div>
        <h2 className="kid-title text-2xl font-bold mb-2" style={{ color: ts.title, textShadow: ts.titleShadow }}>放松一下 🌿</h2>
        <p className="text-sm mb-6 text-center" style={{ color: ts.sub }}>选一个练习，让身体和心情都放松下来</p>

        {/* 今日练习计数 */}
        <TodayCounter ts={ts} />

        <div className="w-full space-y-3">
          {exercises.map((ex) => (
            <button
              key={ex.id}
              onClick={() => setActive(ex)}
              className="w-full flex items-center gap-4 p-4 rounded-2xl transition-all active:scale-[0.98] text-left hover:scale-[1.01] backdrop-blur-md"
              style={{ background: ts.cardBg, border: ts.cardBorder, boxShadow: ts.cardShadow }}
            >
              <span className="w-12 h-12 flex items-center justify-center rounded-2xl text-2xl flex-shrink-0"
                style={{ background: CATEGORY_ACCENT[ex.category] || ts.pillBg }}>
                {CATEGORY_EMOJI[ex.category]}
              </span>
              <div className="flex-1 min-w-0">
                <div className="font-semibold text-base" style={{ color: ts.title }}>{ex.name}</div>
                <div className="text-xs mt-0.5 truncate" style={{ color: ts.muted }}>{ex.description.slice(0, 30)}...</div>
              </div>
              <span className="text-xs font-medium px-2.5 py-1 rounded-full flex-shrink-0"
                style={{ background: ts.pillBg, color: ts.pillText, border: ts.pillBorder }}>{ex.durationSeconds}s</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
