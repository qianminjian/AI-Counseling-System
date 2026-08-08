// 风险等级单一源（FA-01，DOC-074）
// 领域语义：0=绿色（安全）/ 1=黄色 / 2=橙色 / 3=红色（预警处置强度递增）
// 此前 6 处各自定义（AlertQueue/StudentPanel/TodayTodoPanel/OverviewPanel/BigScreen/StatsCharts），
// 已产生 0 级缺失渲染 undefined、1/2 级同色、0='安全' vs '绿色' 标签分歧等实际漂移。
// 消费方：Tag（antdColor）/ 大屏与 ECharts canvas（hex，不支持 CSS var）。

export interface RiskLevelMeta {
  antdColor: string
  hex: string
  label: string
}

export const RISK_LEVEL_META: Record<number, RiskLevelMeta> = {
  0: { antdColor: 'default', hex: '#52c41a', label: '绿色' },
  1: { antdColor: 'gold', hex: '#ffd54f', label: '黄色' },
  2: { antdColor: 'orange', hex: '#ff9800', label: '橙色' },
  3: { antdColor: 'red', hex: '#f44336', label: '红色' },
}

/** antd Tag 颜色；未知/越界等级回退 default（灰），不渲染 undefined */
export function riskColor(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.antdColor ?? 'default'
}

/** 等级文案；未知/越界回退「未知」 */
export function riskLabel(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.label ?? '未知'
}

/** ECharts/canvas hex 色（不支持 CSS var）；未知/越界回退灰 */
export function riskHex(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.hex ?? '#999999'
}
