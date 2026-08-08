/**
 * 沉浸式场景主题适配样式单源（FA-02，DOC-074）
 *
 * 此前 EmotionDiary / RelaxationExercises 各持一份 THEME_STYLES（15 个公共字段逐字重复），
 * Achievements 另硬编码 isDark 判断——三处漂移：新增主题需同步改 3 个文件，改漏即白字白底。
 * 以 RelaxationExercises 版（含 cardBlur/progressTrack/circleFrom/circleTo 扩展字段）为基线，
 * 并集 EmotionDiary 的 input* 字段；各组件只消费自己所需字段。
 */

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
    circleFrom: '#38bdf8',
    circleTo: '#0284c7',
    inputBg: 'rgba(255,255,255,0.12)',
    inputBorder: 'rgba(255,255,255,0.24)',
    inputFocus: 'rgba(125,211,252,0.65)',
    glow: 'rgba(56,189,248,0.55)',
    btnBg: 'linear-gradient(135deg, #0ea5e9, #06b6d4)',
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
    circleFrom: '#f9a8d4',
    circleTo: '#ec4899',
    inputBg: 'rgba(255,255,255,0.65)',
    inputBorder: 'rgba(244,114,182,0.32)',
    inputFocus: 'rgba(236,72,153,0.6)',
    glow: 'rgba(236,72,153,0.4)',
    btnBg: 'linear-gradient(135deg, #ec4899, #a855f7)',
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
    circleFrom: '#a78bfa',
    circleTo: '#7c3aed',
    inputBg: 'rgba(139,92,246,0.14)',
    inputBorder: 'rgba(139,92,246,0.38)',
    inputFocus: 'rgba(167,139,250,0.75)',
    glow: 'rgba(139,92,246,0.55)',
    btnBg: 'linear-gradient(135deg, #8b5cf6, #6d28d9)',
  },
} as const

/** 主题是否深色场景（Achievements 等仅需明暗判断的组件用；未知/缺失主题回退 ocean 语义） */
export function isDarkTheme(themeId: string | undefined | null): boolean {
  return THEME_STYLES[themeId]?.dark ?? THEME_STYLES.ocean.dark
}
