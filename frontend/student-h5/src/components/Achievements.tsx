import { useState, useEffect } from 'react'
import { api } from '../api'

/** 成就徽章展示 */
export default function Achievements() {
  const [badges, setBadges] = useState([])
  const [show, setShow] = useState(false)

  useEffect(() => {
    api('/diary/achievements').then(setBadges).catch(() => {})
  }, [])

  const unlockedCount = badges.filter(b => b.unlocked).length

  return (
    <div style={{ marginTop: 16 }}>
      <button onClick={() => setShow(!show)} className="btn-ghost"
        style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
        🏅 我的成就 ({unlockedCount}/{badges.length})
      </button>

      {show && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10, marginTop: 12 }}>
          {badges.map(b => (
            <div key={b.id} style={{
              textAlign: 'center', padding: '12px 6px', borderRadius: 12,
              background: b.unlocked ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.04)',
              opacity: b.unlocked ? 1 : 0.4,
              border: b.unlocked ? '1px solid rgba(255,215,0,0.4)' : '1px solid rgba(255,255,255,0.08)',
            }}>
              <div style={{ fontSize: 28 }}>{b.emoji}</div>
              <div style={{ fontSize: 11, fontWeight: 600, marginTop: 4 }}>{b.title}</div>
              <div style={{ fontSize: 9, opacity: 0.7, marginTop: 2 }}>{b.desc}</div>
              {b.unlocked && <div style={{ fontSize: 9, color: '#ffd700', marginTop: 3 }}>✓ 已解锁</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
