/**
 * 沉浸式场景主题适配样式单源（FA-02，DOC-074）
 *
 * 此前 EmotionDiary / RelaxationExercises 各持一份 THEME_STYLES（15 个公共字段逐字重复），
 * Achievements 另硬编码 isDark 判断——三处漂移：新增主题需同步改 3 个文件，改漏即白字白底。
 * 以 RelaxationExercises 版（含 cardBlur/progressTrack/circleFrom/circleTo 扩展字段）为基线，
 * 并集 EmotionDiary 的 input* 字段；各组件只消费自己所需字段。
 *
 * FA-13：与 THEMES 双轨 token 收敛——按钮渐变/圆圈渐变等与 ThemeProvider 同语义的色值
 * 从 THEMES 的 vars/bobo 派生（改主色单点生效）；garden.circleFrom 有意独立（浅色场景
 * 渐变起点比 bobo.body 更亮），注释标明例外；其余沉浸式专属字段保留字面量。
 */

import { THEMES } from './ThemeProvider'

export const THEME_STYLES = {
  ocean: {
    dark: true,
    title: '#ffffff',
    titleShadow: '0 2px 12px rgba(0,0,0,0.25)',
    sub: 'rgba(224,242,254,0.78)',
    text: 'rgba(240,249,255,0.94)',
    muted: 'rgba(186,230,253,0.62)',
    back: '#7dd3fc',
    cardBg: 'rgba(255,255,255,0.10)',
    cardBorder: '1px solid rgba(255,255,255,0.18)',
    cardShadow: '0 8px 32px rgba(2,132,199,0.25)',
    cardBlur: 'blur(12px)',
    pillBg: 'rgba(125,211,252,0.16)',
    pillBorder: '1px solid rgba(125,211,252,0.32)',
    pillText: '#bae6fd',
    progressTrack: 'rgba(255,255,255,0.16)',
    circleFrom: THEMES.ocean.bobo.body, // = 波波品牌色
    circleTo: THEMES.ocean.bobo.fin,   // = 波波鳍色
    inputBg: 'rgba(255,255,255,0.12)',
    inputBorder: 'rgba(255,255,255,0.24)',
    inputFocus: 'rgba(125,211,252,0.65)',
    glow: 'rgba(56,189,248,0.55)',
    btnBg: `linear-gradient(135deg, ${THEMES.ocean.vars['--primary']}, ${THEMES.ocean.vars['--accent']})`,
  },
  garden: {
    dark: false,
    title: '#9d174d',
    titleShadow: 'none',
    sub: 'rgba(157,23,77,0.62)',
    text: '#831843',
    muted: 'rgba(190,24,93,0.55)',
    back: '#db2777',
    cardBg: 'rgba(255,255,255,0.82)',
    cardBorder: '1px solid rgba(244,114,182,0.28)',
    cardShadow: '0 8px 28px rgba(236,72,153,0.14)',
    cardBlur: 'blur(6px)',
    pillBg: 'rgba(244,114,182,0.12)',
    pillBorder: '1px solid rgba(244,114,182,0.32)',
    pillText: '#be185d',
    progressTrack: 'rgba(236,72,153,0.12)',
    // FA-13 例外：浅色场景渐变起点有意比 bobo.body 更亮（#f9a8d4 vs #F472B6），不派生
    circleFrom: '#f9a8d4',
    circleTo: THEMES.garden.vars['--primary'],
    inputBg: 'rgba(255,255,255,0.65)',
    inputBorder: 'rgba(244,114,182,0.32)',
    inputFocus: 'rgba(236,72,153,0.6)',
    glow: 'rgba(236,72,153,0.4)',
    btnBg: `linear-gradient(135deg, ${THEMES.garden.vars['--primary']}, ${THEMES.garden.vars['--accent']})`,
  },
  rainbow: {
    dark: true,
    title: '#e0e7ff',
    titleShadow: '0 0 18px rgba(139,92,246,0.4)',
    sub: 'rgba(165,180,252,0.82)',
    text: 'rgba(224,231,255,0.94)',
    muted: 'rgba(129,140,248,0.72)',
    back: '#a5b4fc',
    cardBg: 'rgba(139,92,246,0.12)',
    cardBorder: '1px solid rgba(139,92,246,0.30)',
    cardShadow: '0 8px 32px rgba(76,29,149,0.35)',
    cardBlur: 'blur(12px)',
    pillBg: 'rgba(139,92,246,0.18)',
    pillBorder: '1px solid rgba(167,139,250,0.38)',
    pillText: '#c4b5fd',
    progressTrack: 'rgba(139,92,246,0.20)',
    circleFrom: THEMES.rainbow.bobo.body, // = 波波品牌色
    circleTo: THEMES.rainbow.bobo.fin,   // = 波波鳍色
    inputBg: 'rgba(139,92,246,0.14)',
    inputBorder: 'rgba(139,92,246,0.38)',
    inputFocus: 'rgba(167,139,250,0.75)',
    glow: 'rgba(139,92,246,0.55)',
    btnBg: `linear-gradient(135deg, ${THEMES.rainbow.vars['--primary']}, ${THEMES.rainbow.vars['--primary-dark']})`,
  },
} as const

/** 主题是否深色场景（Achievements 等仅需明暗判断的组件用；未知/缺失主题回退 ocean 语义） */
export function isDarkTheme(themeId: string | undefined | null): boolean {
  return THEME_STYLES[themeId]?.dark ?? THEME_STYLES.ocean.dark
}
