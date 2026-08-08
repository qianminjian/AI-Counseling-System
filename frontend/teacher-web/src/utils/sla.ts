import dayjs from 'dayjs'

/**
 * 预警 SLA 策略（前端镜像后端 AlertSlaPolicy，design/05 §13）
 * 安全兜底铁律：S0「5 分钟必须有人接住」。
 * - S0/RED:   5 min 未认领 → 升级
 * - S1/ORANGE:15 min 未认领 → 升级
 * - S2/YELLOW:60 min → 提醒（不升级）
 * - S3/GREEN: 无 SLA
 */

// riskLevel 数字（3=红/S0, 2=橙/S1, 1=黄/S2, 0=绿/S3）→ SLA 分钟
const SLA_MINUTES_BY_LEVEL: Record<number, number> = { 3: 5, 2: 15, 1: 60, 0: 0 }

export function getSlaMinutes(riskLevel: number): number {
  return SLA_MINUTES_BY_LEVEL[riskLevel] ?? 0
}

/**
 * 计算 SLA 状态。
 * @param {number} riskLevel 风险等级 0-3
 * @param {string} status open/claimed/resolved/false_positive
 * @param {string|Date} detectedAt 检测时间
 * @returns {{breached:boolean, escalate:boolean, overdueMin:number, remainingMin:number, hasSla:boolean}}
 */
export function evaluateSla(riskLevel: number, status: string, detectedAt: string | Date) {
  const slaMin = getSlaMinutes(riskLevel)
  if (slaMin <= 0 || !detectedAt) {
    return { breached: false, escalate: false, overdueMin: 0, remainingMin: 0, hasSla: false }
  }
  // 已关闭的不评估
  if (status === 'resolved' || status === 'false_positive' || status === 'closed') {
    return { breached: false, escalate: false, overdueMin: 0, remainingMin: 0, hasSla: true }
  }

  const elapsedMin = dayjs().diff(dayjs(detectedAt), 'minute')
  const remaining = slaMin - elapsedMin

  if (remaining > 0) {
    return { breached: false, escalate: false, overdueMin: 0, remainingMin: remaining, hasSla: true }
  }

  const overdueMin = -remaining
  // S0/S1 open 超时 → 升级；S2 仅提醒
  const escalate = riskLevel >= 2 && status === 'open'
  return { breached: true, escalate, overdueMin, remainingMin: 0, hasSla: true }
}

/**
 * 格式化 SLA 倒计时为人类可读文本。
 * @returns {string} 如 "剩 8min" / "逾期 12min" / "无时限"
 */
export function formatSlaCountdown(riskLevel: number, status: string, detectedAt: string | Date): string {
  const sla = evaluateSla(riskLevel, status, detectedAt)
  if (!sla.hasSla) return '无时限'
  if (!sla.breached && !sla.escalate && sla.remainingMin > 0) return `剩 ${sla.remainingMin}min`
  if (sla.breached) return `逾期 ${sla.overdueMin}min`
  return '已关闭'
}

/**
 * 待办排序权重：逾期 > SLA 剩余时间（越小越紧急）。
 * 返回数字，升序排列即紧急度从高到低。
 */
export function urgencyWeight(riskLevel: number, status: string, detectedAt: string | Date): number {
  const sla = evaluateSla(riskLevel, status, detectedAt)
  if (!sla.hasSla) return Number.MAX_SAFE_INTEGER // 无 SLA 排最后
  if (sla.breached) return -sla.overdueMin * 1000 - riskLevel // 逾期：越久越靠前
  return sla.remainingMin * 1000 - riskLevel // 未逾期：剩余越少越靠前
}
