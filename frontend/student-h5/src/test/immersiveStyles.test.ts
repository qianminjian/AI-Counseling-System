import { describe, expect, it } from 'vitest'
import { THEME_STYLES, isDarkTheme } from '../theme/immersiveStyles'

describe('theme/immersiveStyles 单源（FA-02，DOC-074）', () => {
  it('三主题字段键完全一致（并集无缺漏，新增主题必须补齐全部字段）', () => {
    const keys = Object.keys(THEME_STYLES.ocean).sort()
    for (const theme of ['ocean', 'garden', 'rainbow'] as const) {
      expect(Object.keys(THEME_STYLES[theme]).sort()).toEqual(keys)
    }
  })

  it('公共字段逐字一致（防漂移：此前 EmotionDiary/RelaxationExercises 双副本）', () => {
    expect(THEME_STYLES.ocean.muted).toBe('rgba(186,230,253,0.62)')
    expect(THEME_STYLES.ocean.cardBg).toBe('rgba(255,255,255,0.10)')
    expect(THEME_STYLES.ocean.btnBg).toBe('linear-gradient(135deg, #0ea5e9, #06b6d4)')
    expect(THEME_STYLES.garden.pillText).toBe('#be185d')
    expect(THEME_STYLES.rainbow.glow).toBe('rgba(139,92,246,0.55)')
    // RelaxationExercises 扩展字段（超集基线）
    expect(THEME_STYLES.ocean.cardBlur).toBe('blur(12px)')
    expect(THEME_STYLES.ocean.progressTrack).toBe('rgba(255,255,255,0.16)')
    expect(THEME_STYLES.garden.circleFrom).toBe('#f9a8d4')
    // EmotionDiary 扩展字段（input*）
    expect(THEME_STYLES.ocean.inputBg).toBe('rgba(255,255,255,0.12)')
    expect(THEME_STYLES.rainbow.inputFocus).toBe('rgba(167,139,250,0.75)')
  })

  it('isDarkTheme：ocean/rainbow 深色、garden 浅色', () => {
    expect(isDarkTheme('ocean')).toBe(true)
    expect(isDarkTheme('rainbow')).toBe(true)
    expect(isDarkTheme('garden')).toBe(false)
  })

  it('isDarkTheme：未知/缺失主题回退 ocean 语义（不误判为浅色）', () => {
    expect(isDarkTheme('forest')).toBe(true)
    expect(isDarkTheme(undefined)).toBe(true)
    expect(isDarkTheme(null)).toBe(true)
  })
})
