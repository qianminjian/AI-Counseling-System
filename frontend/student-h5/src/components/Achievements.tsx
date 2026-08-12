import { useState, useEffect } from 'react'
import { api } from '../api'
import { useTheme } from '../theme/ThemeProvider'
import { isDarkTheme } from '../theme/immersiveStyles'

/** 成就徽章数据结构（/diary/achievements 返回） */
// FE-003：显式类型（此前 useState([]) 推断为 never[] → 属性访问报 TS2339）
interface AchievementBadge {
  id: string
  unlocked: boolean
  emoji: string
  title: string
  desc: string
}

/** 成就徽章展示 */
export default function Achievements() {
  const [badges, setBadges] = useState<AchievementBadge[]>([])
  const [show, setShow] = useState(false)
  const { themeId } = useTheme()

  useEffect(() => {
    api('/diary/achievements').then(setBadges).catch(() => {})
  }, [])

  const unlockedCount = badges.filter(b => b.unlocked).length
  // FA-02：明暗判断收敛至沉浸式色板单源（此前硬编码 ocean/rainbow 列表，新增主题改漏即白字白底）
  const isDark = isDarkTheme(themeId)

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
                  ? 'bg-white/20 border-yellow-400/60 opacity-100'
                  : 'bg-white/90 border-yellow-300 opacity-100'
                : isDark
                  ? 'bg-white/8 border-white/15 opacity-60'
                  : 'bg-white/50 border-gray-200 opacity-50'
              }`}
            >
              <div className="text-2xl">{b.emoji}</div>
              <div className={`text-xs font-semibold mt-1 ${isDark ? 'text-white/90' : 'text-gray-700'}`}>{b.title}</div>
              <div className={`text-[10px] mt-0.5 ${isDark ? 'text-white/70' : 'text-gray-400'}`}>{b.desc}</div>
              {b.unlocked && <div className="text-[10px] text-yellow-400 mt-1 font-medium">✓ 已解锁</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
