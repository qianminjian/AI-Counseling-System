import { useEffect, useState } from 'react'
import { recordMoodCheck } from '../api/toolboxApi'
import type { ToolboxTool } from '../api/toolboxApi'
import { getToolSteps } from '../data/toolSteps'

/**
 * 工具练习界面（F-2，design/36 §3.2 统一工具框架）
 *
 * 流程：练习前心情打分（可选）→ 练习（分步引导 + 倒计时）→ 练习后心情打分（可选）→ 完成反馈
 * - 步骤内容包来自 data/toolSteps.ts（静态打包，离线可用）；
 *   未配置内容包的工具降级为纯倒计时（开闭原则，新工具零框架改动）
 * - 步骤按建议时长自动推进，也可点"下一步"手动跳过
 * - 心情打分 1-5 表情脸谱（儿童 CBT 情绪外化）
 * - 心情恶化（needsAttention）→ 温和引导话术，不指责
 * - recordMoodCheck 失败不阻塞完成界面（可用性优先于埋点）
 */

const MOODS = [
  { score: 1, emoji: '😢', label: '很不好' },
  { score: 2, emoji: '😕', label: '不太好' },
  { score: 3, emoji: '😐', label: '一般般' },
  { score: 4, emoji: '🙂', label: '还不错' },
  { score: 5, emoji: '😊', label: '很好' },
]

function formatTime(sec: number) {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

type Phase = 'pre-mood' | 'practice' | 'post-mood' | 'done'

function MoodPicker({ onPick, selected }: { onPick: (score: number) => void; selected: number | null }) {
  return (
    <div className="flex gap-3 justify-center my-6">
      {MOODS.map((m) => (
        <button
          key={m.score}
          type="button"
          onClick={() => onPick(m.score)}
          aria-label={`心情 ${m.score} 分 ${m.label}`}
          className={`text-3xl p-2 rounded-2xl transition-transform ${
            selected === m.score ? 'bg-amber-100 scale-125 border-2 border-amber-400' : 'hover:scale-110'
          }`}
        >
          {m.emoji}
        </button>
      ))}
    </div>
  )
}

export default function ToolPractice({ tool, onClose }: { tool: ToolboxTool; onClose: () => void }) {
  const steps = getToolSteps(tool.toolId)
  const [phase, setPhase] = useState<Phase>(tool.preMoodCheck ? 'pre-mood' : 'practice')
  const [preMood, setPreMood] = useState<number | null>(null)
  const [postMood, setPostMood] = useState<number | null>(null)
  const [secondsLeft, setSecondsLeft] = useState(tool.durationSec)
  const [stepIndex, setStepIndex] = useState(0)
  const [stepSecondsLeft, setStepSecondsLeft] = useState(steps?.[0]?.durationSec ?? 0)
  const [needsAttention, setNeedsAttention] = useState(false)

  // 练习倒计时（可提前完成）；有内容包时同步推进步骤
  useEffect(() => {
    if (phase !== 'practice') return
    const timer = setInterval(() => {
      setSecondsLeft((s) => (s > 0 ? s - 1 : 0))
      if (steps) {
        setStepSecondsLeft((s) => (s > 0 ? s - 1 : 0))
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [phase, steps])

  // 当前步骤时间到 → 自动推进到下一步；最后一步停留在完成前
  useEffect(() => {
    if (!steps || phase !== 'practice') return
    if (stepSecondsLeft === 0 && stepIndex < steps.length - 1) {
      setStepIndex((i) => i + 1)
      setStepSecondsLeft(steps[stepIndex + 1].durationSec)
    }
  }, [stepSecondsLeft, stepIndex, steps, phase])

  const nextStep = () => {
    if (!steps || stepIndex >= steps.length - 1) return
    setStepIndex(stepIndex + 1)
    setStepSecondsLeft(steps[stepIndex + 1].durationSec)
  }

  const finishPractice = async () => {
    if (tool.postMoodCheck && postMood !== null && preMood !== null) {
      try {
        const result = await recordMoodCheck(tool.toolId, preMood, postMood)
        setNeedsAttention(result?.needsAttention === true)
      } catch {
        // 静默：心情记录失败不阻塞完成界面
      }
    }
    setPhase('done')
  }

  const handleComplete = () => {
    if (phase === 'practice') {
      if (tool.postMoodCheck) {
        setPhase('post-mood')
      } else {
        finishPractice()
      }
    } else if (phase === 'post-mood') {
      finishPractice()
    }
  }

  return (
    <div className="fixed inset-0 z-50 bg-sky-50/95 backdrop-blur flex flex-col items-center justify-center p-6">
      <button
        type="button"
        onClick={onClose}
        aria-label="关闭"
        className="absolute top-4 right-4 text-2xl text-slate-400 hover:text-slate-600"
      >
        ✕
      </button>

      {phase === 'pre-mood' && (
        <div className="text-center max-w-sm">
          <div className="text-5xl mb-3">{tool.emoji}</div>
          <h2 className="text-xl font-bold text-slate-700 mb-2">练习前，你现在的心情是？</h2>
          <MoodPicker selected={preMood} onPick={setPreMood} />
          <button
            type="button"
            disabled={preMood === null}
            onClick={() => setPhase('practice')}
            className="mt-4 px-8 py-3 rounded-full bg-teal-500 text-white font-bold disabled:opacity-40"
          >
            开始练习 🚀
          </button>
        </div>
      )}

      {phase === 'practice' && (
        <div className="text-center max-w-sm">
          <div className="text-7xl mb-4 animate-pulse">{tool.emoji}</div>
          <h2 className="text-2xl font-bold text-slate-700 mb-2">{tool.title}</h2>
          <p className="text-4xl font-mono text-teal-600 mb-6" aria-label="倒计时">
            {formatTime(secondsLeft)}
          </p>
          {steps ? (
            <>
              <p className="text-xs text-slate-400 mb-2">第 {stepIndex + 1} / {steps.length} 步</p>
              <p className="text-lg text-slate-700 leading-relaxed mb-6" aria-live="polite">
                {steps[stepIndex].text}
              </p>
            </>
          ) : (
            <p className="text-slate-500 mb-6">跟着波波慢慢来，感觉舒服了随时可以停下～</p>
          )}
          <div className="flex gap-3 justify-center">
            {steps && stepIndex < steps.length - 1 && (
              <button
                type="button"
                onClick={nextStep}
                className="px-6 py-3 rounded-full bg-white text-teal-600 font-bold border-2 border-teal-300"
              >
                下一步 ➡️
              </button>
            )}
            <button
              type="button"
              onClick={handleComplete}
              className="px-8 py-3 rounded-full bg-teal-500 text-white font-bold"
            >
              我完成啦 ✅
            </button>
          </div>
        </div>
      )}

      {phase === 'post-mood' && (
        <div className="text-center max-w-sm">
          <div className="text-5xl mb-3">{tool.emoji}</div>
          <h2 className="text-xl font-bold text-slate-700 mb-2">练习完啦！现在的心情是？</h2>
          <MoodPicker selected={postMood} onPick={setPostMood} />
          <button
            type="button"
            disabled={postMood === null}
            onClick={handleComplete}
            className="mt-4 px-8 py-3 rounded-full bg-teal-500 text-white font-bold disabled:opacity-40"
          >
            完成 ✨
          </button>
        </div>
      )}

      {phase === 'done' && (
        <div className="text-center max-w-sm">
          <div className="text-6xl mb-4">🎉</div>
          <h2 className="text-2xl font-bold text-slate-700 mb-3">太棒啦，你完成了{tool.title}！</h2>
          {needsAttention ? (
            <p className="text-slate-500 leading-relaxed">
              没有好转也没关系，有时候心情需要多一点时间。
              随时可以和波波聊聊，也可以找信任的大人说一说 💙
            </p>
          ) : (
            <p className="text-slate-500 leading-relaxed">波波为你骄傲！下次想练习随时来百宝箱找我 🧰</p>
          )}
          <button
            type="button"
            onClick={onClose}
            className="mt-6 px-8 py-3 rounded-full bg-teal-500 text-white font-bold"
          >
            返回百宝箱
          </button>
        </div>
      )}
    </div>
  )
}
