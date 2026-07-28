/**
 * student-h5 API 工具（JWT 双 Token + 自动刷新）
 * 
 * 共享设备策略：token/user 存 sessionStorage（关闭 tab 自动清除 = 下次必须登录）
 * 设备级标记（如 consent）存 localStorage（跨会话保持）
 */
const TOKEN_KEY = 'mindsafe_student_token'
const REFRESH_KEY = 'mindsafe_student_refresh'
const USER_KEY = 'mindsafe_student_user'

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_KEY)
}

export function setRefreshToken(token) {
  sessionStorage.setItem(REFRESH_KEY, token)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export function getUser() {
  try {
    return JSON.parse(sessionStorage.getItem(USER_KEY))
  } catch {
    return null
  }
}

export function setUser(user) {
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

// ===== 设备级存储（跨会话保持） =====
const CONSENT_KEY = 'mindsafe_consent_done'

/** 设备是否已完成告知同意（跨 tab 保持） */
export function isConsentDone() {
  return localStorage.getItem(CONSENT_KEY) === '1'
}

export function markConsentDone() {
  localStorage.setItem(CONSENT_KEY, '1')
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

/**
 * PIN 码快捷登录（学生用昵称 + 4-6 位数字 PIN）
 * @returns {Promise<{token, refreshToken, userId, displayName, userType}>}
 */
export async function pinLogin(pseudonym, pin) {
  const res = await fetch('/api/v1/auth/pin-login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pseudonym, pin }),
  })
  const json = await res.json()
  if (!json.success) {
    throw new Error(json.message || '登录失败')
  }
  return json.data
}

/**
 * 设置 PIN 码（注册后引导设置，需已登录）
 */
export async function setPin(pin) {
  await api('/auth/set-pin', {
    method: 'POST',
    body: JSON.stringify({ pin }),
  })
}
