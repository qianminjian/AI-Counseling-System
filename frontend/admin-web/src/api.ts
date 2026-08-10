/** 平台管理端 API（ADMIN-P0-02/04：登录 + token 存取 + 服务状态） */

export interface PlatformLoginResult {
  token: string
  role: string
  displayName: string
}

const TOKEN_KEY = 'admin_token'
const ROLE_KEY = 'admin_role'
const NAME_KEY = 'admin_name'

export async function platformLogin(username: string, password: string): Promise<PlatformLoginResult> {
  const resp = await fetch('/api/v1/platform/auth/login', {
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
  localStorage.setItem(TOKEN_KEY, data.token)
  localStorage.setItem(ROLE_KEY, data.role)
  localStorage.setItem(NAME_KEY, data.displayName ?? '')
  return data
}

export function getAdminToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getAdminRole(): string | null {
  return localStorage.getItem(ROLE_KEY)
}

export function getAdminName(): string {
  return localStorage.getItem(NAME_KEY) ?? ''
}

export function adminLogout(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(NAME_KEY)
}

/** 登录态失效事件（401/403 时触发，App 监听后回登录页，code-review M2） */
export const UNAUTHORIZED_EVENT = 'admin:unauthorized'

/** 平台鉴权请求封装（PLATFORM_ token 前缀由后端签发，此处原样携带） */
export async function adminFetch<T>(path: string): Promise<T> {
  const token = getAdminToken()
  const resp = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (resp.status === 401 || resp.status === 403) {
    adminLogout()
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    throw new Error(resp.status === 403 ? '无权限访问' : '登录已过期')
  }
  if (!resp.ok) {
    throw new Error(`请求失败 (${resp.status})`)
  }
  const body = await resp.json()
  return body.data as T
}

/** 服务健康状态（P0-05） */
export interface ServiceStatus {
  [service: string]: string
}

export function fetchServicesStatus(): Promise<ServiceStatus> {
  return adminFetch<ServiceStatus>('/api/v1/ops/services/status')
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
  return adminFetch<SysConfigItem[]>(`/api/v1/platform/config/registry${q}`)
}

export async function updateConfig(key: string, value: string, reason: string): Promise<void> {
  const token = getAdminToken()
  const resp = await fetch(`/api/v1/platform/config/${encodeURIComponent(key)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ value, reason }),
  })
  if (!resp.ok) {
    const body = await resp.json().catch((): null => null)
    throw new Error(body?.message ?? '配置修改失败')
  }
}

export interface RiskOverview {
  levelDistribution: Record<string, number>
  todayNew: number
  unhandled: number
  trend7d: Record<string, number>
}

export function fetchRiskOverview(): Promise<RiskOverview> {
  return adminFetch<RiskOverview>('/api/v1/ops/risk/overview')
}

export function fetchRiskOverdue(): Promise<Array<Record<string, unknown>>> {
  return adminFetch<Array<Record<string, unknown>>>('/api/v1/ops/risk/overdue')
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
  return adminFetch<DegradationRow[]>('/api/v1/ops/degradation/matrix')
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
  return adminFetch<DegradationEventItem[]>(`/api/v1/ops/degradation/events${q}`)
}

/** 手动切换（X-Confirm 固定短语 + reason） */
export async function degradationOverride(point: string, to: string, reason: string): Promise<void> {
  const token = getAdminToken()
  const resp = await fetch(`/api/v1/ops/degradation/${encodeURIComponent(point)}/override`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Confirm': 'CONFIRM',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ to, reason }),
  })
  if (!resp.ok) {
    const body = await resp.json().catch((): null => null)
    throw new Error(body?.message ?? '切换失败')
  }
}

/** 取消覆盖（回配置默认，X-Confirm + reason） */
export async function cancelDegradationOverride(point: string, reason: string): Promise<void> {
  const token = getAdminToken()
  const resp = await fetch(`/api/v1/ops/degradation/${encodeURIComponent(point)}/override/cancel`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Confirm': 'CONFIRM',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ reason }),
  })
  if (!resp.ok) {
    const body = await resp.json().catch((): null => null)
    throw new Error(body?.message ?? '取消失败')
  }
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

export async function promptAction(path: string, body?: unknown): Promise<void> {
  const token = getAdminToken()
  const resp = await fetch(`/api/v1/admin/prompts${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!resp.ok) {
    const b = await resp.json().catch((): null => null)
    throw new Error(b?.message ?? '操作失败')
  }
}

export function fetchSlaStats(): Promise<Array<Record<string, unknown>>> {
  return adminFetch<Array<Record<string, unknown>>>('/api/v1/ops/risk/sla-stats')
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
  return adminFetch<DeadLedgerItem[]>('/api/v1/ops/insights/dead-ledger')
}

export function fetchKnowledgeStats(): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>('/api/v1/ops/knowledge/stats')
}

export function fetchQualityTrend(): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>('/api/v1/ops/insights/quality-trend')
}

export function fetchAlertFunnel(): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>('/api/v1/ops/insights/alert-funnel')
}

export function fetchTenantHealth(): Promise<Array<Record<string, unknown>>> {
  return adminFetch<Array<Record<string, unknown>>>('/api/v1/ops/insights/tenant-health')
}

export function fetchUsageSummary(days = 30): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>(`/api/v1/ops/usage/summary?days=${days}`)
}

export function fetchConsentStats(): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>('/api/v1/ops/compliance/consent-stats')
}

/** Prompt 版本列表（M7，走 adminFetch 统一鉴权/登出联动） */
export function fetchPromptVersions(templateKey: string): Promise<PromptVersionItem[]> {
  return adminFetch<PromptVersionItem[]>(`/api/v1/admin/prompts/versions?templateKey=${encodeURIComponent(templateKey)}`)
}

/** 通知渠道统计（M10） */
export function fetchChannelStats(): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>('/api/v1/ops/insights/channel-stats')
}

// ===== M2 指标看板 + 告警中心（ADMIN-P1-07/08/09） =====

/** 指标看板：白名单表达式代理查询 Prometheus（P1-07） */
export function fetchMetricsQuery(expr: string): Promise<Record<string, unknown>> {
  return adminFetch<Record<string, unknown>>(`/api/v1/ops/metrics/query?expr=${encodeURIComponent(expr)}`)
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
  return adminFetch<AlertEventItem[]>(`/api/v1/ops/alert-events${q}`)
}

/** 告警确认（firing → ack，X-Confirm 二次确认 + reason 必填，仅 ops/super） */
export async function ackAlertEvent(eventId: string, reason: string): Promise<void> {
  const token = getAdminToken()
  const resp = await fetch(`/api/v1/ops/alerts/${eventId}/ack`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Confirm': 'CONFIRM',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ reason }),
  })
  if (resp.status === 401 || resp.status === 403) {
    adminLogout()
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    throw new Error(resp.status === 403 ? '无权限访问（仅运维/超管可确认告警）' : '登录已过期')
  }
  if (!resp.ok) {
    const b = await resp.json().catch((): null => null)
    throw new Error(b?.message ?? '确认失败')
  }
}
