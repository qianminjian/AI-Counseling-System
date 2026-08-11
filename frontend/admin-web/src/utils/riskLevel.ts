/**
 * 风险等级共享常量（doing/90 P-009：RiskPage/SlaPage/LedgerPage 三处重复收敛）
 * 等级语义：0=GREEN/平稳，1=YELLOW/轻度波动，2=ORANGE/需关注，3=RED/高危
 */
export const RISK_LEVEL_NAMES: Record<number, string> = {
  0: 'GREEN',
  1: 'YELLOW',
  2: 'ORANGE',
  3: 'RED',
}

export const RISK_LEVEL_COLORS: Record<number, string> = {
  0: 'green',
  1: 'yellow',
  2: 'orange',
  3: 'red',
}

/** 数字等级 → 名称（未知等级回退 GREEN） */
export function riskLevelName(level: unknown): string {
  const n = Number(level)
  return RISK_LEVEL_NAMES[n] ?? RISK_LEVEL_NAMES[0]
}

/** 数字等级 → antd Tag 颜色 */
export function riskLevelColor(level: unknown): string {
  const n = Number(level)
  return RISK_LEVEL_COLORS[n] ?? RISK_LEVEL_COLORS[0]
}

/** 数字等级 → 小写 key（用于 CSS token 字典查值，如 riskTokenMap[riskLevelKey(l)]） */
export function riskLevelKey(level: unknown): string {
  return riskLevelColor(level)
}
