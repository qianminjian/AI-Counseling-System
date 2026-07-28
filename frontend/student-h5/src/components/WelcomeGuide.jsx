import { useState, useRef } from 'react'

const WELCOME_KEY = 'mindsafe_welcome_done'

const SLIDES = [
  { emoji: '👋', title: '嗨，欢迎来到心灵小屋！', desc: '这里是你的秘密空间，说什么都可以哦', bg: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' },
  { emoji: '🐬', title: '和波波说说话', desc: '按住波波就能说话，开心或不开心的事，都可以告诉它', bg: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)' },
  { emoji: '🔒', title: '你说的都是安全的', desc: '只有学校心理老师能看到，爸爸妈妈不会看到', bg: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)' },
  { emoji: '🌈', title: '准备好了吗？', desc: '选一个你现在的心情，开始吧！', bg: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)' },
]

/** 学生端首次使用引导（支持滑动手势 + CSS 动画） */
export default function WelcomeGuide() {
  const [visible, setVisible] = useState(() => !localStorage.getItem(WELCOME_KEY))
  const [step, setStep] = useState(0)
  const touchStartX = useRef(0)

  if (!visible) return null

  const finish = () => {
    localStorage.setItem(WELCOME_KEY, 'true')
    setVisible(false)
  }

  const goNext = () => step < SLIDES.length - 1 ? setStep(s => s + 1) : finish()
  const goPrev = () => step > 0 && setStep(s => s - 1)

  const handleTouchStart = (e) => { touchStartX.current = e.touches[0].clientX }
  const handleTouchEnd = (e) => {
    const delta = e.changedTouches[0].clientX - touchStartX.current
    if (delta < -50) goNext()
    else if (delta > 50) goPrev()
  }

  const slide = SLIDES[step]

  return (
    <div
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
      style={{
        position: 'fixed', inset: 0, zIndex: 9999,
        background: slide.bg,
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        padding: 32, textAlign: 'center', color: '#fff',
        transition: 'background 0.5s ease',
      }}
    >
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

      {/* 进度点（可点击跳转） */}
      <div style={{ display: 'flex', gap: 8, margin: '32px 0' }}>
        {SLIDES.map((_, i) => (
          <button
            key={i}
            onClick={() => setStep(i)}
            aria-label={`第 ${i + 1} 页`}
            style={{
              width: i === step ? 24 : 8, height: 8, borderRadius: 4,
              background: i === step ? '#fff' : 'rgba(255,255,255,0.3)',
              transition: 'all 0.3s', border: 'none', cursor: 'pointer', padding: 0,
            }}
          />
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
        <button onClick={goNext} style={{
          padding: '10px 28px', borderRadius: 20, border: 'none',
          background: '#fff', color: '#764ba2', fontSize: 14, fontWeight: 600, cursor: 'pointer',
        }}>
          {step < SLIDES.length - 1 ? '下一步' : '开始使用 🎉'}
        </button>
      </div>

      {/* 滑动提示 */}
      {step < SLIDES.length - 1 && (
        <p style={{ position: 'absolute', bottom: 32, fontSize: 12, opacity: 0.5 }}>
          ← 左右滑动也可以翻页 →
        </p>
      )}

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
