import { getToken, getRefreshToken, setToken, setRefreshToken, clearAuth } from '../utils/auth'
// DC-005：认证传输收敛为共享模块（SPEC §19）——parent 适配层
//（键名历史差异 parent_refresh_token，utils/auth 的 '' 语义适配共享 TokenStorage 的 null 语义）
import { refreshTokens } from '../../../shared/src/auth-transport/refresh'
import { handleSessionExpired } from '../../../shared/src/auth-transport/sessionExpired'
import { toApiError } from '../../../shared/src/auth-transport/apiError'
import type { TokenStorage } from '../../../shared/src/auth-transport/tokenStorage'

const BASE_URL = '/api/v1'

/** parent 适配层：保持 utils/auth 现状（getToken 返回 ''），适配共享 TokenStorage（null）语义 */
const storage: TokenStorage = {
  getToken: () => getToken() || null,
  getRefreshToken: () => getRefreshToken() || null,
  setToken,
  setRefreshToken,
  clear: clearAuth,
}

interface RequestOptions {
  method?: string
  headers?: Record<string, string>
  data?: unknown
  _retried?: boolean
}

interface ApiResponse<T = unknown> {
  code?: number
  message?: string
  data?: T
  success?: boolean
}

/**
 * 统一请求封装（含 401 自动刷新）
 */
async function request<T = unknown>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const token = getToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {})
  }

  const res = await fetch(`${BASE_URL}${path}`, {
    method: options.method || 'GET',
    headers,
    body: options.data ? JSON.stringify(options.data) : undefined
  })

  // 401 自动刷新
  if (res.status === 401 && !options._retried) {
    const refreshed = await refreshTokens(storage)
    if (refreshed) {
      return request<T>(path, { ...options, _retried: true })
    }
    // DC-005：统一 401 登出决策点（clear + 跳转登录页 + throw）
    handleSessionExpired(storage, '/parent/')
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as ApiResponse
    throw toApiError({ code: body.code, message: body.message || `请求失败 (${res.status})` })
  }

  return res.json() as Promise<ApiResponse<T>>
}

// ========== 家长认证 API ==========

export interface RegisterData {
  familyCode: string
  phone: string
  password: string
  relation: string
}

export interface LoginData {
  phone: string
  password: string
}

export interface AuthResult {
  token: string
  refreshToken?: string
  parentId: string
  displayName: string
  children: Array<{ userId: string; nickname: string; gradeCode?: string; classCode?: string }>
}

/** 家长注册（家庭码 + 手机号 + 密码 + 关系） */
export function parentRegister(data: RegisterData) {
  return request<AuthResult>('/parent/auth/register', { method: 'POST', data })
}

/** 家长登录（手机号 + 密码） */
export function parentLogin(data: LoginData) {
  return request<AuthResult>('/parent/auth/login', { method: 'POST', data })
}

// ========== 家长数据 API ==========

export interface ReportData {
  sessionCount: number
  totalTurns: number
  maxRiskLevel: number
  riskLabel: string
  emotionDistribution: Record<string, number>
}

/** 获取情绪周报（指定学生） */
export function getReport(studentUserId: string) {
  return request<ReportData>(`/parent/report?studentUserId=${studentUserId}`)
}

/** 撤回同意 */
export function withdrawConsent(studentUserId: string) {
  return request<{ message?: string }>('/parent/consent/withdraw', {
    method: 'POST',
    data: { studentUserId }
  })
}
