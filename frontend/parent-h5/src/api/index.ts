import { getToken, getRefreshToken, setToken, setRefreshToken, clearAuth } from '../utils/auth'

const BASE_URL = '/api/v1'

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
    const refreshed = await tryRefresh()
    if (refreshed) {
      return request<T>(path, { ...options, _retried: true })
    }
    clearAuth()
    window.location.href = '/parent/'
    throw new Error('登录已过期，请重新登录')
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({})) as ApiResponse
    throw new Error(body.message || `请求失败 (${res.status})`)
  }

  return res.json() as Promise<ApiResponse<T>>
}

async function tryRefresh(): Promise<boolean> {
  const rt = getRefreshToken()
  if (!rt) return false
  try {
    const res = await fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt })
    })
    const json = await res.json() as ApiResponse<{ token: string; refreshToken: string }>
    if (json.success && json.data?.token) {
      setToken(json.data.token)
      setRefreshToken(json.data.refreshToken)
      return true
    }
  } catch { /* ignore */ }
  return false
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
