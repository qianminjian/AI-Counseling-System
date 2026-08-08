import { describe, expect, it } from 'vitest'
import { THEME_STYLES, isDarkTheme } from '../theme/immersiveStyles'
import { THEMES } from '../theme/ThemeProvider'

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

  it('FA-13：跨体系一致性——THEME_STYLES 色板从 THEMES 派生（改主色单点生效，防白字白底）', () => {
    const norm = (s: string) => s.toLowerCase()
    // btnBg 首色 = --primary（三主题）
    for (const theme of ['ocean', 'garden', 'rainbow'] as const) {
      expect(norm(THEME_STYLES[theme].btnBg)).toContain(norm(THEMES[theme].vars['--primary']))
    }
    // 渐变第二色 = --accent（ocean/garden）或 --primary-dark（rainbow）
    expect(norm(THEME_STYLES.ocean.btnBg)).toContain(norm(THEMES.ocean.vars['--accent']))
    expect(norm(THEME_STYLES.garden.btnBg)).toContain(norm(THEMES.garden.vars['--accent']))
    expect(norm(THEME_STYLES.rainbow.btnBg)).toContain(norm(THEMES.rainbow.vars['--primary-dark']))
    // 圆圈渐变 = bobo 品牌色（garden.circleFrom 有意例外，仅断言可对应项）
    expect(THEME_STYLES.ocean.circleFrom).toBe(THEMES.ocean.bobo.body)
    expect(THEME_STYLES.ocean.circleTo).toBe(THEMES.ocean.bobo.fin)
    expect(THEME_STYLES.rainbow.circleFrom).toBe(THEMES.rainbow.bobo.body)
    expect(THEME_STYLES.rainbow.circleTo).toBe(THEMES.rainbow.bobo.fin)
    expect(THEME_STYLES.garden.circleTo).toBe(THEMES.garden.vars['--primary'])
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
