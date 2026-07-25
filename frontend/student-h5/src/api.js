/**
 * student-h5 API 工具（JWT token 管理 + 请求封装）
 */
const TOKEN_KEY = 'mindsafe_student_token'
const USER_KEY = 'mindsafe_student_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
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

/**
 * 通用 API 请求（自动携带 JWT）
 * @param {string} path - 相对路径，如 '/chat/sessions'
 * @param {RequestInit} options - fetch 选项
 * @returns {Promise<any>} - 响应 data 字段
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
