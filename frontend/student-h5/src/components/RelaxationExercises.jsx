import { useState, useEffect, useRef } from 'react'
import { api } from '../api'

const CATEGORY_EMOJI = {
  breathing: '🌬️',
  mindfulness: '🧘',
  visualization: '🌈',
  somatic: '🦋',
}

/** 呼吸引导动画圆圈（CSS keyframe 平滑动画） */
function BreathingCircle({ phase, seconds }) {
  const phaseConfig = {
    inhale: { label: '吸气', color: '#a8d8ea', anim: 'breathe-in' },
    hold: { label: '屏住', color: '#b8e6c8', anim: 'breathe-hold' },
    exhale: { label: '呼气', color: '#c8b8e6', anim: 'breathe-out' },
  }
  const cfg = phaseConfig[phase] || phaseConfig.inhale

  return (
    <div className="flex flex-col items-center gap-3">
      <style>{`
        @keyframes breathe-in { from { transform: scale(0.8); } to { transform: scale(1.3); } }
        @keyframes breathe-hold { from { transform: scale(1.3); } to { transform: scale(1.3); } }
        @keyframes breathe-out { from { transform: scale(1.3); } to { transform: scale(0.8); } }
        @keyframes pulse-ring { 0% { box-shadow: 0 0 0 0 rgba(168,216,234,0.4); } 100% { box-shadow: 0 0 0 20px rgba(168,216,234,0); } }
      `}</style>
      <div
        className="w-36 h-36 rounded-full flex items-center justify-center"
        style={{
          animation: `${cfg.anim} ${seconds}s ease-in-out forwards, pulse-ring 2s infinite`,
          background: `linear-gradient(135deg, ${cfg.color}, var(--primary))`,
        }}
      >
        <span className="text-white text-lg font-medium">{cfg.label}</span>
      </div>
      <span className="text-3xl font-light text-gray-500">{seconds}</span>
    </div>
  )
}

/** 单个练习执行器 */
function ExerciseRunner({ exercise, onComplete, onBack }) {
  const [phase, setPhase] = useState('inhale')
  const [seconds, setSeconds] = useState(0)
  const [elapsed, setElapsed] = useState(0)
  const [done, setDone] = useState(false)
  const timerRef = useRef(null)

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

    return () => clearInterval(timerRef.current)
  }, [])

  useEffect(() => {
    if (elapsed >= exercise.durationSeconds && !done) {
      setDone(true)
      clearInterval(timerRef.current)
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

  if (done) {
    return (
      <div className="flex flex-col items-center gap-4 py-8">
        <span className="text-5xl">🎉</span>
        <p className="text-lg text-gray-700">做得好！感觉放松一些了吗？</p>
        <button
          onClick={onComplete}
          className="px-8 py-3 rounded-full text-white text-sm"
          style={{ background: 'var(--primary)' }}
        >
          完成
        </button>
      </div>
    )
  }

  const isBreathing = exercise.category === 'breathing'

  return (
    <div className="flex flex-col items-center gap-6 py-6">
      <h3 className="text-lg font-medium text-gray-700">{exercise.name}</h3>

      {isBreathing ? (
        <BreathingCircle phase={phase} seconds={seconds} />
      ) : (
        <div className="text-center px-6">
          <span className="text-5xl block mb-4">{CATEGORY_EMOJI[exercise.category]}</span>
          <p className="text-gray-600 leading-relaxed">{exercise.description}</p>
        </div>
      )}

      {/* 进度条 */}
      <div className="w-full max-w-xs">
        <div className="h-2 bg-gray-100 rounded-full overflow-hidden">
          <div
            className="h-full rounded-full transition-all duration-1000"
            style={{
              width: `${Math.min((elapsed / exercise.durationSeconds) * 100, 100)}%`,
              background: 'var(--primary)',
            }}
          />
        </div>
        <div className="flex justify-between mt-1 text-xs text-gray-400">
          <span>{elapsed}s</span>
          <span>{exercise.durationSeconds}s</span>
        </div>
      </div>

      <button onClick={onBack} className="text-sm text-gray-400 underline">
        提前结束
      </button>
    </div>
  )
}

/** 今日练习计数 */
function TodayCounter() {
  const [count, setCount] = useState(0)
  useEffect(() => {
    api('/relaxation/sessions/today').then(d => setCount(d.count || 0)).catch(() => {})
  }, [])
  if (count === 0) return null
  return (
    <div className="mb-6 px-4 py-2 bg-white/70 rounded-full text-sm text-gray-500 shadow-sm">
      ✨ 今天已完成 {count} 次练习，继续加油！
    </div>
  )
}

/**
 * 放松练习面板（从 EmotionSelect 页面进入）
 */
export default function RelaxationExercises({ onBack }) {
  const [exercises, setExercises] = useState([])
  const [active, setActive] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api('/relaxation/exercises')
      .then(setExercises)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  if (active) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center p-6"
        style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
        <ExerciseRunner
          exercise={active}
          onComplete={() => setActive(null)}
          onBack={() => setActive(null)}
        />
      </div>
    )
  }

  return (
    <div className="min-h-screen flex flex-col items-center p-6 pt-12"
      style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
      <button onClick={onBack} className="self-start text-sm text-gray-400 mb-6">← 返回</button>

      <h2 className="text-xl font-medium text-gray-800 mb-2">放松一下 🌿</h2>
      <p className="text-sm text-gray-400 mb-4">选一个练习，让身体和心情都放松下来</p>

      {/* 今日练习计数 */}
      <TodayCounter />

      <div className="w-full max-w-sm space-y-3">
        {exercises.map((ex) => (
          <button
            key={ex.id}
            onClick={() => setActive(ex)}
            className="w-full flex items-center gap-4 p-4 bg-white rounded-2xl shadow-sm
              hover:shadow-md active:scale-[0.98] transition-all text-left"
          >
            <span className="text-3xl">{CATEGORY_EMOJI[ex.category]}</span>
            <div className="flex-1">
              <div className="font-medium text-gray-700">{ex.name}</div>
              <div className="text-xs text-gray-400 mt-0.5">{ex.description.slice(0, 30)}...</div>
            </div>
            <span className="text-xs text-gray-300">{ex.durationSeconds}s</span>
          </button>
        ))}
      </div>
    </div>
  )
}
