import { useState, useEffect } from 'react'
import { api } from '../api'
import { useTheme } from '../theme/ThemeProvider'

/** 成就徽章展示 */
export default function Achievements() {
  const [badges, setBadges] = useState([])
  const [show, setShow] = useState(false)
  const { themeId } = useTheme()

  useEffect(() => {
    api('/diary/achievements').then(setBadges).catch(() => {})
  }, [])

  const unlockedCount = badges.filter(b => b.unlocked).length
  const isDark = themeId === 'ocean' || themeId === 'rainbow'

  return (
    <div className="mt-4 w-full">
      <button
        onClick={() => setShow(!show)}
        className={`w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-2xl text-sm font-medium transition-all active:scale-95
          ${isDark
            ? 'bg-white/10 border border-white/20 text-white/80 hover:bg-white/20'
            : 'bg-white/60 border border-pink-100 text-pink-700 hover:bg-white/80'
          }`}
      >
        🏅 我的成就 ({unlockedCount}/{badges.length})
      </button>

      {show && badges.length > 0 && (
        <div className="grid grid-cols-3 gap-2.5 mt-3">
          {badges.map(b => (
            <div key={b.id} className={`text-center p-3 rounded-xl border transition-all
              ${b.unlocked
                ? isDark
                  ? 'bg-white/12 border-yellow-400/40 opacity-100'
                  : 'bg-white/80 border-yellow-300 opacity-100'
                : isDark
                  ? 'bg-white/4 border-white/8 opacity-40'
                  : 'bg-white/40 border-gray-200 opacity-40'
              }`}
            >
              <div className="text-2xl">{b.emoji}</div>
              <div className={`text-xs font-semibold mt-1 ${isDark ? 'text-white' : 'text-gray-700'}`}>{b.title}</div>
              <div className={`text-[10px] mt-0.5 ${isDark ? 'text-white/60' : 'text-gray-400'}`}>{b.desc}</div>
              {b.unlocked && <div className="text-[10px] text-yellow-400 mt-1 font-medium">✓ 已解锁</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
