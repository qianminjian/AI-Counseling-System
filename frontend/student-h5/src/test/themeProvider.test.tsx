import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { ThemeProvider, useTheme, THEMES } from '../theme/ThemeProvider'
import React from 'react'

describe('theme/ThemeProvider', () => {
  beforeEach(() => {
    localStorage.clear()
    // 清理 document.documentElement 上的 inline styles
    document.documentElement.removeAttribute('style')
  })

  describe('THEMES 配置', () => {
    it('包含 3 个主题：ocean/garden/rainbow', () => {
      expect(Object.keys(THEMES)).toEqual(['ocean', 'garden', 'rainbow'])
    })

    it('每个主题包含必要字段', () => {
      for (const t of Object.values(THEMES)) {
        expect(t).toHaveProperty('id')
        expect(t).toHaveProperty('name')
        expect(t).toHaveProperty('emoji')
        expect(t).toHaveProperty('companion')
        expect(t).toHaveProperty('bobo')
        expect(t).toHaveProperty('vars')
        expect(t.vars).toHaveProperty('--primary')
        expect(t.vars).toHaveProperty('--bg-start')
      }
    })

    it('波波配色包含 body/belly/fin', () => {
      for (const t of Object.values(THEMES)) {
        expect(t.bobo).toHaveProperty('body')
        expect(t.bobo).toHaveProperty('belly')
        expect(t.bobo).toHaveProperty('fin')
      }
    })
  })

  describe('useTheme', () => {
    const wrapper = ({ children }) => React.createElement(ThemeProvider, null, children)

    it('默认主题为 ocean', () => {
      const { result } = renderHook(() => useTheme(), { wrapper })
      expect(result.current.themeId).toBe('ocean')
      expect(result.current.theme.name).toBe('海洋探险')
    })

    it('从 localStorage 恢复主题', () => {
      localStorage.setItem('mindsafe_theme_v1', 'garden')
      const { result } = renderHook(() => useTheme(), { wrapper })
      expect(result.current.themeId).toBe('garden')
    })

    it('changeTheme 切换并持久化', () => {
      const { result } = renderHook(() => useTheme(), { wrapper })
      act(() => { result.current.changeTheme('rainbow') })
      expect(result.current.themeId).toBe('rainbow')
      expect(localStorage.getItem('mindsafe_theme_v1')).toBe('rainbow')
    })

    it('changeTheme 忽略无效 ID', () => {
      const { result } = renderHook(() => useTheme(), { wrapper })
      act(() => { result.current.changeTheme('invalid') })
      expect(result.current.themeId).toBe('ocean')
    })

    it('应用 CSS Variables 到 documentElement', () => {
      renderHook(() => useTheme(), { wrapper })
      const style = document.documentElement.style
      expect(style.getPropertyValue('--primary')).toBe('#0EA5E9')
    })

    it('切换主题后 CSS Variables 更新', () => {
      const { result } = renderHook(() => useTheme(), { wrapper })
      act(() => { result.current.changeTheme('garden') })
      expect(document.documentElement.style.getPropertyValue('--primary')).toBe('#EC4899')
    })

    it('useTheme 在 Provider 外抛出错误', () => {
      expect(() => {
        renderHook(() => useTheme())
      }).toThrow('useTheme must be used within ThemeProvider')
    })
  })
})
