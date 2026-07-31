import { useMemo } from 'react'

/**
 * 三主题动画背景装饰（登录页 / 情绪选择页共用）
 * - ocean：气泡上浮 + 游鱼 + 海底
 * - garden：糖果漂浮
 * - rainbow：星星闪烁 + 行星 + 流星
 * 依赖 index.css 中 .bubble/.fish/.sea-floor/.candy-float/.star/.planet/.shooting-star
 */
export default function SceneDecor({ themeId }) {
  const items = useMemo(() => {
    if (themeId === 'ocean') {
      return {
        bubbles: Array.from({ length: 8 }, (_, i) => ({
          id: i,
          size: 8 + Math.random() * 24,
          left: `${5 + Math.random() * 90}%`,
          dur: `${6 + Math.random() * 8}s`,
          delay: `${Math.random() * 5}s`,
        })),
        fish: [
          { emoji: '🐠', top: '25%', dur: '14s', delay: '0s' },
          { emoji: '🐟', top: '55%', dur: '18s', delay: '3s' },
          { emoji: '🐡', top: '72%', dur: '16s', delay: '7s' },
        ],
      }
    }
    if (themeId === 'garden') {
      return {
        candies: ['🍬', '🍭', '🧁', '🍩', '🍪', '🎀'].map((emoji, i) => ({
          id: i, emoji,
          left: `${8 + i * 16}%`,
          top: `${10 + (i % 3) * 30}%`,
          delay: `${i * 0.7}s`,
        })),
      }
    }
    // rainbow
    return {
      stars: Array.from({ length: 30 }, (_, i) => ({
        id: i,
        left: `${Math.random() * 100}%`,
        top: `${Math.random() * 100}%`,
        delay: `${Math.random() * 3}s`,
      })),
      planets: [
        { size: 40, color: 'rgba(139,92,246,0.3)', left: '12%', top: '18%' },
        { size: 24, color: 'rgba(236,72,153,0.25)', left: '80%', top: '30%' },
        { size: 16, color: 'rgba(6,182,212,0.3)', left: '65%', top: '70%' },
      ],
    }
  }, [themeId])

  if (themeId === 'ocean') {
    return (
      <>
        {items.bubbles.map((b) => (
          <div key={b.id} className="bubble" style={{ width: b.size, height: b.size, left: b.left, bottom: '-30px', animationDuration: b.dur, animationDelay: b.delay }} />
        ))}
        {items.fish.map((f, i) => (
          <span key={i} className="fish" style={{ top: f.top, animationDuration: f.dur, animationDelay: f.delay, fontSize: 22 }}>{f.emoji}</span>
        ))}
        <div className="sea-floor" />
      </>
    )
  }
  if (themeId === 'garden') {
    return (
      <>
        {items.candies.map((c) => (
          <span key={c.id} className="candy-float" style={{ left: c.left, top: c.top, animationDelay: c.delay, fontSize: 28 }}>{c.emoji}</span>
        ))}
      </>
    )
  }
  return (
    <>
      {items.stars.map((s) => (
        <div key={s.id} className="star" style={{ left: s.left, top: s.top, animationDelay: s.delay }} />
      ))}
      {items.planets.map((p, i) => (
        <div key={i} className="planet" style={{ width: p.size, height: p.size, background: p.color, left: p.left, top: p.top }} />
      ))}
      <div className="shooting-star" style={{ top: '15%', left: '20%', animationDelay: '1s' }} />
      <div className="shooting-star" style={{ top: '40%', left: '60%', animationDelay: '3.5s' }} />
    </>
  )
}
