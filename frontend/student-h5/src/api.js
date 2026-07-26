/**
 * student-h5 API 工具（JWT 双 Token + 自动刷新）
 */
const TOKEN_KEY = 'mindsafe_student_token'
const REFRESH_KEY = 'mindsafe_student_refresh'
const USER_KEY = 'mindsafe_student_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_KEY)
}

export function setRefreshToken(token) {
  localStorage.setItem(REFRESH_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_KEY)
}

export function getUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY))
  } catch {
    return null
  }
}

export function setUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 是否已登录（有有效 token） */
export function isAuthenticated() {
  return !!getToken()
}

/** 尝试刷新 Token，成功返回 true */
async function tryRefresh() {
  const rt = getRefreshToken()
  if (!rt) return false
  try {
    const res = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
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

/**
 * 通用 API 请求（自动携带 JWT + 401 自动刷新）
 */
export async function api(path, options = {}) {
  const token = getToken()
  const res = await fetch(`/api/v1${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })

  if (res.status === 401) {
    // 尝试刷新
    if (await tryRefresh()) {
      // 重试原请求
      const newToken = getToken()
      const retry = await fetch(`/api/v1${path}`, {
        ...options,
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${newToken}`,
          ...options.headers,
        },
      })
      const json = await retry.json()
      if (!json.success) throw new Error(json.message || '请求失败')
      return json.data
    }
    clearToken()
    window.location.reload()
    throw new Error('登录已过期，请重新进入')
  }

  const json = await res.json()
  if (!json.success) {
    throw new Error(json.message || '请求失败')
  }
  return json.data
}

/**
 * 试用注册
 * @returns {Promise<{token, userId, tenantId, userType, pseudonym}>}
 */
export async function trialRegister(data) {
  const res = await fetch('/api/v1/auth/trial/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  const json = await res.json()
  if (!json.success) {
    throw new Error(json.message || '注册失败')
  }
  return json.data
}
