import { useState } from 'react'

const WELCOME_KEY = 'mindsafe_welcome_done'

const SLIDES = [
  { emoji: '👋', title: '嗨，欢迎来到心灵小屋！', desc: '这里是你的秘密空间，说什么都可以哦' },
  { emoji: '💬', title: '和 AI 小伙伴聊天', desc: '开心或不开心的事，都可以告诉它' },
  { emoji: '🔒', title: '你说的都是安全的', desc: '只有学校心理老师能看到，爸爸妈妈不会看到' },
  { emoji: '🌈', title: '准备好了吗？', desc: '选一个你现在的心情，开始吧！' },
]

/** 学生端首次使用引导（CSS 动画） */
export default function WelcomeGuide() {
  const [visible, setVisible] = useState(() => !localStorage.getItem(WELCOME_KEY))
  const [step, setStep] = useState(0)

  if (!visible) return null

  const finish = () => {
    localStorage.setItem(WELCOME_KEY, 'true')
    setVisible(false)
  }

  const slide = SLIDES[step]

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 9999,
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
      padding: 32, textAlign: 'center', color: '#fff',
    }}>
      {/* 弹跳 emoji */}
      <div key={step} style={{ fontSize: 72, animation: 'bounceIn 0.6s ease' }}>
        {slide.emoji}
      </div>

      <h2 key={`t-${step}`} style={{ fontSize: 22, fontWeight: 700, margin: '24px 0 12px', animation: 'fadeUp 0.5s ease' }}>
        {slide.title}
      </h2>
      <p style={{ fontSize: 15, opacity: 0.85, lineHeight: 1.6, animation: 'fadeUp 0.6s ease' }}>
        {slide.desc}
      </p>

      {/* 进度点 */}
      <div style={{ display: 'flex', gap: 8, margin: '32px 0' }}>
        {SLIDES.map((_, i) => (
          <div key={i} style={{
            width: 8, height: 8, borderRadius: '50%',
            background: i === step ? '#fff' : 'rgba(255,255,255,0.3)',
            transition: 'all 0.3s',
          }} />
        ))}
      </div>

      {/* 按钮 */}
      <div style={{ display: 'flex', gap: 12 }}>
        <button onClick={finish} style={{
          padding: '10px 20px', borderRadius: 20, border: '1px solid rgba(255,255,255,0.5)',
          background: 'transparent', color: '#fff', fontSize: 14, cursor: 'pointer',
        }}>
          跳过
        </button>
        <button onClick={() => step < SLIDES.length - 1 ? setStep(s => s + 1) : finish()} style={{
          padding: '10px 28px', borderRadius: 20, border: 'none',
          background: '#fff', color: '#764ba2', fontSize: 14, fontWeight: 600, cursor: 'pointer',
        }}>
          {step < SLIDES.length - 1 ? '下一步' : '开始使用 🎉'}
        </button>
      </div>

      <style>{`
        @keyframes bounceIn {
          0% { transform: scale(0.3); opacity: 0; }
          50% { transform: scale(1.1); }
          100% { transform: scale(1); opacity: 1; }
        }
        @keyframes fadeUp {
          from { transform: translateY(16px); opacity: 0; }
          to { transform: translateY(0); opacity: 1; }
        }
      `}</style>
    </div>
  )
}
