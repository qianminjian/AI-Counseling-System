import { getToken } from '../utils/auth.js'

const BASE_URL = '/api/v1'

/**
 * 统一请求封装
 * P2 迁移小程序时：将 fetch 替换为 Taro.request，逻辑不变
 */
async function request(path, options = {}) {
  const token = options._token || getToken()
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

  if (res.status === 401) {
    window.location.href = '/parent/'
    throw new Error('登录已过期，请重新验证')
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.message || `请求失败 (${res.status})`)
  }

  return res.json()
}

// ========== 家长端 API ==========

/** 发送手机验证码（需初始 token） */
export function sendCode(phone, initialToken) {
  return request('/parent/send-code', {
    method: 'POST',
    data: { phone },
    _token: initialToken
  })
}

/** 验证手机 + 签发正式 Token */
export function verifyPhone(phone, code, initialToken) {
  return request('/parent/verify-phone', {
    method: 'POST',
    data: { phone, code },
    _token: initialToken
  })
}

/** 获取情绪周报 */
export function getReport() {
  return request('/parent/report')
}

/** 撤回同意 */
export function withdrawConsent() {
  return request('/parent/consent/withdraw', { method: 'POST' })
}
