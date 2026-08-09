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
