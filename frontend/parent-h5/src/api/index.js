import { getToken, getRefreshToken, setToken, setRefreshToken, clearAuth } from '../utils/auth.js'

const BASE_URL = '/api/v1'

/**
 * 统一请求封装（含 401 自动刷新）
 */
async function request(path, options = {}) {
  const token = getToken()
  const headers = {
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
      return request(path, { ...options, _retried: true })
    }
    clearAuth()
    window.location.href = '/parent/'
    throw new Error('登录已过期，请重新登录')
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.message || `请求失败 (${res.status})`)
  }

  return res.json()
}

async function tryRefresh() {
  const rt = getRefreshToken()
  if (!rt) return false
  try {
    const res = await fetch(`${BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt })
    })
    const json = await res.json()
    if (json.success && json.data?.token) {
      setToken(json.data.token)
      setRefreshToken(json.data.refreshToken)
      return true
    }
  } catch { /* ignore */ }
  return false
}

// ========== 家长认证 API ==========

/** 家长注册（家庭码 + 手机号 + 密码 + 关系） */
export function parentRegister(data) {
  return request('/parent/auth/register', { method: 'POST', data })
}

/** 家长登录（手机号 + 密码） */
export function parentLogin(data) {
  return request('/parent/auth/login', { method: 'POST', data })
}

/** 查询绑定的学生列表 */
export function getChildren() {
  return request('/parent/auth/children')
}

// ========== 家长数据 API ==========

/** 获取情绪周报（指定学生） */
export function getReport(studentUserId) {
  return request(`/parent/report?studentUserId=${studentUserId}`)
}

/** 撤回同意 */
export function withdrawConsent(studentUserId) {
  return request('/parent/consent/withdraw', {
    method: 'POST',
    data: { studentUserId }
  })
}
