/** 平台管理端 API（ADMIN-P0-02/04：登录 + token 存取 + 服务状态）
 * P0 backlog ②（L1）：token 从 localStorage 迁 sessionStorage（会话级，关闭浏览器自动清除，
 * 对齐 teacher-web AUD-007 先例）；httpOnly cookie 长期方案留作远期。
 * 板块08 P1-1（2026-08-12）：端点路径收敛至 api/endpoints.ts 常量表单一事实源
 * （对齐 teacher-web FA-15），本文件不再出现 API 路径字面量；请求行为不变。
 * FE-004（doing/95，merge develop 合并保留）：token 存取经 shared auth-transport 安全封装
 * （StorageLike 防隐私模式 SecurityError，与三端一致）。 */

import { ENDPOINTS, fillPath } from './api/endpoints'
// DC-005：认证传输共享模块（token 存取/authFetch/登出）
import { createPlatformTokens, type StorageLike } from '../../shared/src/auth-transport/tokenStorage'
// F-10（doing/98）：错误模型统一 shared ApiError（带业务 code，四端契约一致；原抛裸 Error 无 code 无法业务分支）
import { ApiError, toApiError } from '../../shared/src/auth-transport/apiError'

export interface PlatformLoginResult {
  token: string
  role: string
  displayName: string
}

/** 会话级存储安全适配（隐私模式/存储禁用下不抛 SecurityError，FE-004 对齐三端 storage 安全封装） */
const sessionStore: StorageLike = {
  get: (key) => {
    try {
      return sessionStorage.getItem(key)
    } catch {
      return null
    }
  },
  set: (key, value) => {
    try {
      sessionStorage.setItem(key, value)
    } catch {
      /* 隐私模式：静默失败（登出仍可用） */
    }
  },
  remove: (key) => {
    try {
      sessionStorage.removeItem(key)
    } catch {
      /* 同上 */
    }
  },
}

/** 平台单 token 存取（键 admin_token，与历史会话兼容；admin_role/admin_name 另行管理） */
const storage = createPlatformTokens('admin_', sessionStore)
const ROLE_KEY = 'admin_role'
const NAME_KEY = 'admin_name'

/** 平台登录（DEC-007：独立登录端点，PLATFORM_ 登录态与业务 JWT 有意解耦）。
 * 有意保留裸 fetch 不走 postAdmin：登录场景 401 = 账号密码错误（业务失败），
 * 而非会话过期——登出联动/清 token 对登录页无意义，语义不同不合并。 */
export async function platformLogin(username: string, password: string): Promise<PlatformLoginResult> {
  const resp = await fetch(ENDPOINTS.platformLogin.path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!resp.ok) {
    const body = await resp.json().catch((): null => null)
    throw new Error(body?.message ?? '登录失败')
  }
  const body = await resp.json()
  const data = body.data as PlatformLoginResult
  storage.setToken(data.token)
  sessionStore.set(ROLE_KEY, data.role)
  sessionStore.set(NAME_KEY, data.displayName ?? '')
  return data
}

export function getAdminToken(): string | null {
  return storage.getToken()
}

export function getAdminRole(): string | null {
  return sessionStore.get(ROLE_KEY)
}

export function getAdminName(): string {
  return sessionStore.get(NAME_KEY) ?? ''
}

export function adminLogout(): void {
  storage.clear()
  sessionStore.remove(ROLE_KEY)
  sessionStore.remove(NAME_KEY)
}

/** 登录态失效事件（401/403 时触发，App 监听后回登录页，code-review M2） */
export const UNAUTHORIZED_EVENT = 'admin:unauthorized'

/** 平台鉴权 GET 请求封装（PLATFORM_ token 前缀由后端签发，此处原样携带）
 * 板块08 P1-1：path 一律由调用方从 ENDPOINTS 常量表派生（fillPath + query 拼接），本封装不感知具体端点 */
export async function adminFetch<T>(path: string): Promise<T> {
  const token = getAdminToken()
  const resp = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (resp.status === 401 || resp.status === 403) {
    adminLogout()
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    throw new ApiError(resp.status === 403 ? 20002 : 20001, resp.status === 403 ? '无权限访问' : '登录已过期')
  }
  if (!resp.ok) {
    const body = await resp.json().catch((): null => null)
    throw toApiError({ code: (body as { code?: number })?.code, message: (body as { message?: string })?.message ?? `请求失败 (${resp.status})` })
  }
  const body = await resp.json()
  return body.data as T
}

interface PostAdminOptions {
  /** 后端无 body.message 时的兜底文案（语义化错误，替换原各 POST 的“XX 失败”） */
  fallbackMessage?: string
  /** X-Confirm 二次确认头（降级切换/告警确认等高风险操作） */
  xConfirm?: boolean
  /** 403 专属语义化文案（默认“无权限访问”，高风险操作可细化） */
  forbiddenMessage?: string
}

/**
 * 平台 POST 统一封装（板块08 P1-2）：鉴权头 + 401/403 → adminLogout + UNAUTHORIZED_EVENT + 语义化错误。
 * 此前 6 个 POST 封装各自手写 fetch，token 过期时只抛“XX 失败/修改失败”，
 * 用户停留登录失效页却不知已失效——会话过期静默化掩盖真实失败原因。
 * 与 adminFetch 共用同一登出联动模式后，失效态统一回登录页，错误归因不再误导运维。
 */
export async function postAdmin<T = void>(path: string, body?: unknown, options: PostAdminOptions = {}): Promise<T> {
  const token = getAdminToken()
  const resp = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(options.xConfirm ? { 'X-Confirm': 'CONFIRM' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (resp.status === 401 || resp.status === 403) {
    adminLogout()
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    throw new ApiError(resp.status === 403 ? 20002 : 20001, resp.status === 403 ? (options.forbiddenMessage ?? '无权限访问') : '登录已过期')
  }
  if (!resp.ok) {
    const b = await resp.json().catch((): null => null)
    throw toApiError({ code: (b as { code?: number })?.code, message: (b as { message?: string })?.message ?? options.fallbackMessage ?? '操作失败' })
  }
  const b = await resp.json().catch((): null => null)
  return (b?.data ?? undefined) as T
}

/** 服务健康状态（P0-05） */
export interface ServiceStatus {
  [service: string]: string
}

// ===== 平台总览（P0 backlog ⑤ 双轨收敛：从 teacher-web 迁移至 admin-web） =====

/** 平台总览指标（fetchPlatformOverview，F-09 doing/98：显式 VO 替换 Record<string, unknown>） */
export interface PlatformOverviewVO {
  tenantCount: number
  schoolCount: number
  studentCount: number
  teacherCount: number
  totalSessions: number
  totalAlerts: number
  openAlerts: number
}

export function fetchPlatformOverview(): Promise<PlatformOverviewVO> {
  return adminFetch<PlatformOverviewVO>(ENDPOINTS.platformOverview.path)
}

/** 租户列表 */
export interface PlatformTenant {
  tenantName: string
  status: string
  tenantCode: string
  schoolCount: number
  studentCount: number
  teacherCount: number
  sessionCount: number
  createdAt: string
}

export function fetchPlatformTenants(): Promise<PlatformTenant[]> {
  return adminFetch<PlatformTenant[]>(ENDPOINTS.platformTenants.path)
}

export function fetchServicesStatus(): Promise<ServiceStatus> {
  return adminFetch<ServiceStatus>(ENDPOINTS.servicesStatus.path)
}

// ===== P1（ADMIN-P1-01/04：配置注册表 + 风险全景） =====

export interface SysConfigItem {
  configKey: string
  domain: string
  value: string
  valueType: string
  sensitive: string
  effectMode: string
  description?: string
}

export function fetchConfigs(domain?: string): Promise<SysConfigItem[]> {
  const q = domain ? `?domain=${encodeURIComponent(domain)}` : ''
  return adminFetch<SysConfigItem[]>(ENDPOINTS.configRegistry.path + q)
}

export function updateConfig(key: string, value: string, reason: string): Promise<void> {
  return postAdmin(
    fillPath(ENDPOINTS.updateConfig.path, { key: encodeURIComponent(key) }),
    { value, reason },
    { fallbackMessage: '配置修改失败' },
  )
}

// BUG-A-03-01（2026-08-12，UI-TEST-015）：配置变更历史（审计留痕展示）
export interface ConfigHistoryItem {
  historyId: string
  configKey: string
  valueBefore: string
  valueAfter: string
  reason?: string
  operator?: string
  createdAt: string
}

export function fetchConfigHistory(key: string): Promise<ConfigHistoryItem[]> {
  return adminFetch<ConfigHistoryItem[]>(fillPath(ENDPOINTS.configHistory.path, { key: encodeURIComponent(key) }))
}

export interface RiskOverview {
  levelDistribution: Record<string, number>
  todayNew: number
  unhandled: number
  trend7d: Record<string, number>
}

export function fetchRiskOverview(): Promise<RiskOverview> {
  return adminFetch<RiskOverview>(ENDPOINTS.riskOverview.path)
}

/** 逾期风险事件（fetchRiskOverdue，F-09 doing/98） */
export interface RiskOverdueItem {
  riskEventId: string
  tenantId: string
  tenantCode?: string
  tenantName?: string
  riskType: string
  riskLevel: number
  status: string
  detectedAt: string
}

export function fetchRiskOverdue(): Promise<RiskOverdueItem[]> {
  return adminFetch<RiskOverdueItem[]>(ENDPOINTS.riskOverdue.path)
}

// ===== P2（ADMIN-P2-01/02：降级矩阵 + 事件时间线） =====

export interface DegradationRow {
  point: string
  overridden: boolean
  overrideTo?: string
  currentState: string
  availableStates: string[]
  latestEvent?: { from: string; to: string; triggerType: string; occurredAt: string }
}

export function fetchDegradationMatrix(): Promise<DegradationRow[]> {
  return adminFetch<DegradationRow[]>(ENDPOINTS.degradationMatrix.path)
}

export interface DegradationEventItem {
  point: string
  fromState: string
  toState: string
  triggerType: string
  operator?: string
  occurredAt: string
}

export function fetchDegradationEvents(point?: string): Promise<DegradationEventItem[]> {
  const q = point ? `?point=${encodeURIComponent(point)}` : ''
  return adminFetch<DegradationEventItem[]>(ENDPOINTS.degradationEvents.path + q)
}

/** 手动切换（X-Confirm 固定短语 + reason） */
export function degradationOverride(point: string, to: string, reason: string): Promise<void> {
  return postAdmin(
    fillPath(ENDPOINTS.degradationOverride.path, { point: encodeURIComponent(point) }),
    { to, reason },
    { fallbackMessage: '切换失败', xConfirm: true },
  )
}

/** 取消覆盖（回配置默认，X-Confirm + reason） */
export function cancelDegradationOverride(point: string, reason: string): Promise<void> {
  return postAdmin(
    fillPath(ENDPOINTS.cancelDegradationOverride.path, { point: encodeURIComponent(point) }),
    { reason },
    { fallbackMessage: '取消失败', xConfirm: true },
  )
}

// ===== 前端余页（P1-09/P2-06/P3-02：Prompt 管理/时效/台账/知识库/洞察/用量/合规） =====

export interface PromptVersionItem {
  versionId: string
  templateKey: string
  version: number
  description?: string
  abGroup: string
  isActive: boolean
  status?: string
  contentLength: number
}

/** Prompt 操作（提交审核/审核/激活，子路径由调用方传入，基路径取自常量表） */
export function promptAction(path: string, body?: unknown): Promise<void> {
  return postAdmin(ENDPOINTS.promptAction.path + path, body, { fallbackMessage: '操作失败' })
}

/** SLA 时效统计行（fetchSlaStats，F-09 doing/98） */
export interface SlaStatsItem {
  riskLevel: number
  total: number
  onTime: number
  overdue: number
  onTimeRate: number
  p95Minutes: number
}

export function fetchSlaStats(): Promise<SlaStatsItem[]> {
  return adminFetch<SlaStatsItem[]>(ENDPOINTS.slaStats.path)
}

export interface DeadLedgerItem {
  riskEventId: string
  tenantId: string
  riskLevel: number
  riskType: string
  status: string
  detectedAt: string
  notifyStatus: string
}

export function fetchDeadLedger(): Promise<DeadLedgerItem[]> {
  return adminFetch<DeadLedgerItem[]>(ENDPOINTS.deadLedger.path)
}

/** 知识库统计（fetchKnowledgeStats，F-09 doing/98） */
export interface KnowledgeStatsVO {
  byCategory: Record<string, number>
  byStatus: Record<string, number>
}

export function fetchKnowledgeStats(): Promise<KnowledgeStatsVO> {
  return adminFetch<KnowledgeStatsVO>(ENDPOINTS.knowledgeStats.path)
}

/** 质量趋势（fetchQualityTrend：日期 → 日均分/样本数，F-09 doing/98） */
export type QualityTrendVO = Record<string, { avgScore: number; samples: number }>

export function fetchQualityTrend(): Promise<QualityTrendVO> {
  return adminFetch<QualityTrendVO>(ENDPOINTS.qualityTrend.path)
}

/** 告警漏斗（fetchAlertFunnel：阶段 → 计数，F-09 doing/98） */
export type AlertFunnelVO = Record<string, number>

export function fetchAlertFunnel(): Promise<AlertFunnelVO> {
  return adminFetch<AlertFunnelVO>(ENDPOINTS.alertFunnel.path)
}

/** 租户健康行（fetchTenantHealth，F-09 doing/98） */
export interface TenantHealthItem {
  tenantId?: string
  tenantCode?: string
  tenantName: string
  total: number
  unhandled: number
  overdue: number
  health: string
}

export function fetchTenantHealth(): Promise<TenantHealthItem[]> {
  return adminFetch<TenantHealthItem[]>(ENDPOINTS.tenantHealth.path)
}

/** 用量汇总（fetchUsageSummary，F-09 doing/98；索引签名兼容指标键） */
export interface UsageSummaryVO {
  windowDays?: number
  [key: string]: number | string | undefined
}

export function fetchUsageSummary(days = 30): Promise<UsageSummaryVO> {
  return adminFetch<UsageSummaryVO>(`${ENDPOINTS.usageSummary.path}?days=${days}`)
}

/** 同意统计（fetchConsentStats，F-09 doing/98） */
export interface ConsentStatsVO {
  total: number
  last7d: number
  byType: Record<string, number>
}

export function fetchConsentStats(): Promise<ConsentStatsVO> {
  return adminFetch<ConsentStatsVO>(ENDPOINTS.consentStats.path)
}

/** Prompt 版本列表（M7，走 adminFetch 统一鉴权/登出联动） */
export function fetchPromptVersions(templateKey: string): Promise<PromptVersionItem[]> {
  return adminFetch<PromptVersionItem[]>(`${ENDPOINTS.promptVersions.path}?templateKey=${encodeURIComponent(templateKey)}`)
}

/** 通知渠道统计（fetchChannelStats，F-09 doing/98） */
export interface ChannelStatsVO {
  total: number
  byChannel: Record<string, number>
}

export function fetchChannelStats(): Promise<ChannelStatsVO> {
  return adminFetch<ChannelStatsVO>(ENDPOINTS.channelStats.path)
}

// ===== M2 指标看板 + 告警中心（ADMIN-P1-07/08/09） =====

/** 指标查询结果（fetchMetricsQuery，PromQL 即时向量原样透传：{status,data:{result}}，F-09 doing/98） */
export interface MetricsQueryResult {
  status?: string
  data?: { result?: Array<{ metric?: Record<string, string>; value?: [number, string] }> }
}

/** 指标看板：白名单表达式代理查询 Prometheus（P1-07） */
export function fetchMetricsQuery(expr: string): Promise<MetricsQueryResult> {
  return adminFetch<MetricsQueryResult>(`${ENDPOINTS.metricsQuery.path}?expr=${encodeURIComponent(expr)}`)
}

/** 告警事件历史（alert_events 落库台账，P1-08） */
export interface AlertEventItem {
  eventId: string
  source: string
  ruleName: string
  severity: string
  status: string
  summary: string
  detail?: string
  notifyStatus?: string
  acknowledgedBy?: string
  firedAt: string
  resolvedAt?: string
}

export function fetchAlertEvents(status?: string): Promise<AlertEventItem[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return adminFetch<AlertEventItem[]>(ENDPOINTS.alertEvents.path + q)
}

/** 告警确认（firing → ack，X-Confirm 二次确认 + reason 必填，仅 ops/super） */
export function ackAlertEvent(eventId: string, reason: string): Promise<void> {
  return postAdmin(
    fillPath(ENDPOINTS.ackAlertEvent.path, { eventId }),
    { reason },
    { fallbackMessage: '确认失败', xConfirm: true, forbiddenMessage: '无权限访问（仅运维/超管可确认告警）' },
  )
}

// ===== M13 无屏终端设备管理（CFG-008，doing/84 §六.2 平台管理域） =====

export interface PlatformDeviceItem {
  deviceId: string
  deviceCode: string
  deviceType: string
  firmwareVersion?: string
  status: string
  online: boolean
  lastOnlineAt?: string
  binding?: { bindType: string; bindTargetId: string; boundAt?: string } | null
}

/** 跨租户设备列表（状态/归属筛选） */
export function fetchPlatformDevices(status?: string, bindTargetId?: string): Promise<PlatformDeviceItem[]> {
  const qs = new URLSearchParams()
  if (status) qs.set('status', status)
  if (bindTargetId) qs.set('bindTargetId', bindTargetId)
  const query = qs.toString()
  return adminFetch<PlatformDeviceItem[]>(ENDPOINTS.platformDevices.path + (query ? `?${query}` : ''))
}

/** 设备详情（含绑定历史） */
export function fetchPlatformDeviceDetail(deviceId: string): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>(fillPath(ENDPOINTS.platformDeviceDetail.path, { deviceId }))
}

/** 二维码批量签发（印刷包留痕） */
export function exportDeviceQr(deviceCodes: string[]): Promise<{ issuedCount: number; notFound: string[] }> {
  return postAdmin<{ issuedCount: number; notFound: string[] }>(
    ENDPOINTS.exportDeviceQr.path,
    { deviceCodes, issuedBy: getAdminName() },
    { fallbackMessage: '二维码签发失败' },
  )
}

/** 批量操作受理（ota / reboot / factory-reset） */
export function batchDeviceOperation(deviceCodes: string[], action: string): Promise<Record<string, unknown>> {
  return postAdmin<Record<string, unknown>>(
    ENDPOINTS.batchDeviceOperation.path,
    { deviceCodes, action, operator: getAdminName() },
    { fallbackMessage: '批量操作失败' },
  )
}

/** 跨租户审计日志（ADMIN-P0-07：tenantId/action/时间范围筛选，BUG-A-004 补前端消费）
 * D-联动（板块08 P1-4）：与 teacher-web AuditLogVO 收敛为后端 AuditLog 实体同一契约字段命名 */
export interface AuditLogItem {
  auditLogId: string
  tenantId?: string
  userId?: string
  action: string
  resourceType: string
  resourceId?: string
  detail?: string
  ipHash?: string
  userAgent?: string
  createdAt: string
}

export function fetchAuditLogs(limit = 100): Promise<AuditLogItem[]> {
  return adminFetch<AuditLogItem[]>(`${ENDPOINTS.auditLogs.path}?limit=${limit}`)
}
