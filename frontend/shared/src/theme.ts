/**
 * MindSafe 品牌色板单源（doing/92 R-005）
 *
 * 原 teacher-web StatsCharts 本地 TS 常量与 index.css --ms-* token 手工同步（双轨漂移风险）；
 * 现收敛为本模块导出，teacher-web 图表消费统一入口。
 * - primary/primaryDeep/warning/danger 与 teacher-web/src/index.css --ms-* token 同源同值，
 *   一致性由 teacher-web theme-consistency.test.ts 守卫（防漂移）
 * - primarySoft/primaryMid/gradientMid 为 ECharts canvas 专用半透明/渐变变体
 *   （canvas 不支持 CSS var()，CSS 无对应 token）
 */
export const themeColors = {
  primary: '#2BA8A0',
  primaryDeep: '#1E7F7A',
  primarySoft: 'rgba(43, 168, 160, 0.08)',
  primaryMid: 'rgba(43, 168, 160, 0.45)',
  warning: '#D98E32',
  danger: '#D9534F',
  /** 渐变中间色（主色 → 主色加深），ECharts LinearGradient 专用 */
  gradientMid: '#8FD4CF',
} as const
