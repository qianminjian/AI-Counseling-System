// 风险等级单一源（FA-01，DOC-074）
// 领域语义：0=绿色（安全）/ 1=黄色 / 2=橙色 / 3=红色（预警处置强度递增）
// 此前 6 处各自定义（AlertQueue/StudentPanel/TodayTodoPanel/OverviewPanel/BigScreen/StatsCharts），
// 已产生 0 级缺失渲染 undefined、1/2 级同色、0='安全' vs '绿色' 标签分歧等实际漂移。
// 消费方：Tag（antdColor）/ 工作台亮底 ECharts（hex，风险色板契约值）/ 大屏暗底（hexBright，保留项亮色系，不支持 CSS var）。
// hex 与 design/08 §4.1 风险等级色板契约同值（跨端强一致项：R1 绿 #389E0D / R2 黄 #D4B106 / R3 橙 #D46B08 / R4 红 #CF1322）；
// 大屏暗底需更高亮度色阶（doing/75 §7.3 保留项），亮色系收 hexBright。

export interface RiskLevelMeta {
  antdColor: string
  hex: string
  /** 大屏暗底专用亮色（保留项，暗底可视化需高亮度色阶） */
  hexBright: string
  label: string
}

export const RISK_LEVEL_META: Record<number, RiskLevelMeta> = {
  0: { antdColor: 'default', hex: '#389E0D', hexBright: '#52c41a', label: '绿色' },
  1: { antdColor: 'gold', hex: '#D4B106', hexBright: '#ffd54f', label: '黄色' },
  2: { antdColor: 'orange', hex: '#D46B08', hexBright: '#ff9800', label: '橙色' },
  3: { antdColor: 'red', hex: '#CF1322', hexBright: '#f44336', label: '红色' },
}

/** antd Tag 颜色；未知/越界等级回退 default（灰），不渲染 undefined */
export function riskColor(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.antdColor ?? 'default'
}

/** 等级文案；未知/越界回退「未知」 */
export function riskLabel(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.label ?? '未知'
}

/** 工作台亮底 ECharts/canvas hex（风险色板契约值，不支持 CSS var）；未知/越界回退灰 */
export function riskHex(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.hex ?? '#999999'
}

/** 大屏暗底专用 hex（保留项亮色系）；未知/越界回退灰 */
export function riskHexBright(level?: number | null): string {
  return RISK_LEVEL_META[level ?? -1]?.hexBright ?? '#999999'
}
