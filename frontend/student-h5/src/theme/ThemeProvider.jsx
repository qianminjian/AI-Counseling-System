/**
 * 视觉主题系统
 * - 海洋探险（蓝绿）/ 花园精灵（粉紫）/ 彩虹自由（自定义）
 * - CSS Variables 驱动，全局切换
 * - localStorage 持久化
 */
import { createContext, useContext, useState, useEffect, useCallback } from 'react'

// ===== 主题定义 =====

export const THEMES = {
  ocean: {
    id: 'ocean',
    name: '海洋探险',
    emoji: '🌊',
    desc: '和小海豚"波波"一起探索',
    companion: '🐬',
    companionName: '波波',
    vars: {
      '--primary': '#0EA5E9',
      '--primary-light': '#E0F2FE',
      '--primary-dark': '#0369A1',
      '--accent': '#06B6D4',
      '--bg-start': '#F0F9FF',
      '--bg-end': '#E0F7FA',
      '--bubble-ai': '#F0F9FF',
      '--bubble-user': '#DBEAFE',
      '--voice-btn': '#0EA5E9',
      '--card-bg': '#FFFFFF',
    },
  },
  garden: {
    id: 'garden',
    name: '花园精灵',
    emoji: '🌸',
    desc: '和小兔子"棉花糖"一起玩耍',
    companion: '🐰',
    companionName: '棉花糖',
    vars: {
      '--primary': '#EC4899',
      '--primary-light': '#FCE7F3',
      '--primary-dark': '#BE185D',
      '--accent': '#A855F7',
      '--bg-start': '#FDF2F8',
      '--bg-end': '#FAF5FF',
      '--bubble-ai': '#FDF2F8',
      '--bubble-user': '#FCE7F3',
      '--voice-btn': '#EC4899',
      '--card-bg': '#FFFFFF',
    },
  },
  rainbow: {
    id: 'rainbow',
    name: '彩虹自由',
    emoji: '🌈',
    desc: '选择你最喜欢的颜色',
    companion: '⭐',
    companionName: '小星',
    vars: {
      '--primary': '#8B5CF6',
      '--primary-light': '#EDE9FE',
      '--primary-dark': '#6D28D9',
      '--accent': '#F59E0B',
      '--bg-start': '#FAFAFA',
      '--bg-end': '#F5F3FF',
      '--bubble-ai': '#F9FAFB',
      '--bubble-user': '#EDE9FE',
      '--voice-btn': '#8B5CF6',
      '--card-bg': '#FFFFFF',
    },
  },
}

const THEME_KEY = 'mindsafe_theme_v1'

const ThemeContext = createContext(null)

export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(() => {
    return localStorage.getItem(THEME_KEY) || 'ocean'
  })

  const theme = THEMES[themeId] || THEMES.ocean

  // 应用 CSS Variables 到 :root
  useEffect(() => {
    const root = document.documentElement
    Object.entries(theme.vars).forEach(([key, value]) => {
      root.style.setProperty(key, value)
    })
  }, [theme])

  const changeTheme = useCallback((id) => {
    if (THEMES[id]) {
      setThemeId(id)
      localStorage.setItem(THEME_KEY, id)
    }
  }, [])

  return (
    <ThemeContext.Provider value={{ theme, themeId, changeTheme, themes: THEMES }}>
      {children}
    </ThemeContext.Provider>
  )
}

export function useTheme() {
  const ctx = useContext(ThemeContext)
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider')
  return ctx
}
