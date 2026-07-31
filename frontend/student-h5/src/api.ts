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

export function setToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_KEY)
}

export function setRefreshToken(token: string) {
  sessionStorage.setItem(REFRESH_KEY, token)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export function getUser(): Record<string, unknown> | null {
  try {
    return JSON.parse(sessionStorage.getItem(USER_KEY))
  } catch {
    return null
  }
}

export function setUser(user: Record<string, unknown>) {
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

/**
 * 带 JWT 认证的 fetch（自动携带 token + 401 自动刷新重试）
 * 
 * 适用于不经过 api() 的场景（如 multipart 上传、SSE 流、音频下载），
 * 不解析 JSON、不检查 success 字段——调用方自行处理 Response。
 */
export async function authFetch(url: string, init?: RequestInit): Promise<Response> {
  const doFetch = () => {
    const token = getToken()
    return fetch(url, {
      ...init,
      headers: {
        ...(init?.headers instanceof Headers
          ? Object.fromEntries((init.headers as Headers).entries())
          : init?.headers || {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    })
  }
  let res = await doFetch()
  if (res.status === 401) {
    if (await tryRefresh()) {
      res = await doFetch()
    }
  }
  return res
}

/** 尝试刷新 Token，成功返回 true */
export async function tryRefresh() {
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
export async function api(path: string, options: RequestInit & { headers?: Record<string, string> } = {}) {
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

export interface TrialRegisterData {
  inviteCode: string
  pseudonym: string
  age: number
  consentVersion: string
  guardianPhone?: string
  role?: string
  gender?: string
  pin?: string
}

export interface AuthResult {
  token: string
  refreshToken?: string
  userId: string
  tenantId?: string
  userType?: string
  pseudonym?: string
  displayName?: string
  familyCode?: string
  /** age<14 且尚无监护人同意记录 → 需走 SMS 验证码闭环（AUTH-040） */
  guardianConsentPending?: boolean
}

/**
 * 试用注册
 */
export async function trialRegister(data: TrialRegisterData): Promise<AuthResult> {
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
 */
export async function pinLogin(pseudonym: string, pin: string): Promise<AuthResult> {
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
export async function setPin(pin: string): Promise<void> {
  await api('/auth/set-pin', {
    method: 'POST',
    body: JSON.stringify({ pin }),
  })
}

/**
 * 声纹录入后签发设备登录凭证（需已登录）
 * 凭证与声纹模板一起存本机 IndexedDB，声纹登录时凭其换取正式 token
 */
export async function issueVoiceCredential(): Promise<string> {
  const data = await api('/auth/voice-credential', { method: 'POST' })
  return data.voiceCredential
}

/**
 * 声纹登录：本地声纹比对通过后，用设备凭证换取正式双 token
 */
export async function voiceLogin(voiceCredential: string): Promise<AuthResult> {
  const res = await fetch('/api/v1/auth/voice-login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ voiceCredential }),
  })
  const json = await res.json()
  if (!json.success) {
    throw new Error(json.message || '声纹登录失败')
  }
  return json.data
}

/**
 * 发起监护人同意请求：发送短信验证码到监护人手机（AUTH-040，需已登录）
 */
export async function requestGuardianConsent(guardianPhone: string): Promise<void> {
  await api('/auth/guardian-consent/request', {
    method: 'POST',
    body: JSON.stringify({ guardianPhone }),
  })
}

/**
 * 确认监护人同意：校验短信验证码并写入同意记录（AUTH-040，需已登录）
 */
export async function confirmGuardianConsent(guardianPhone: string, code: string): Promise<void> {
  await api('/auth/guardian-consent/confirm', {
    method: 'POST',
    body: JSON.stringify({ guardianPhone, code }),
  })
}

// ===== 声纹双模式（local / remote） =====

export interface VoiceprintConfig {
  mode: 'local' | 'remote'
  privacyNote: string
}

/**
 * 获取声纹模式配置（公开，无需登录）
 * 前端启动时调用，决定走 local 还是 remote 流程
 */
export async function getVoiceprintConfig(): Promise<VoiceprintConfig> {
  const res = await fetch('/api/v1/voiceprint/config')
  const json = await res.json()
  if (!json.success) throw new Error(json.message || '获取声纹配置失败')
  return json.data
}

/**
 * 声纹远程验证登录（remote 模式，公开端点）
 * 前端提取 embedding 后传服务端比对，通过则直接签发双 token
 */
export async function remoteVoiceprintVerify(embeddings: number[][]): Promise<AuthResult & { matched: boolean; score: number }> {
  const res = await fetch('/api/v1/voiceprint/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ embeddings }),
  })
  const json = await res.json()
  if (!json.success) throw new Error(json.message || '声纹验证失败')
  return json.data
}

/**
 * 声纹远程录入（remote 模式，需已登录）
 * 前端提取 embedding 后传服务端存储
 */
export async function remoteVoiceprintEnroll(embeddings: number[][]): Promise<{ enrolled: number }> {
  return api('/voiceprint/enroll', {
    method: 'POST',
    body: JSON.stringify({ embeddings }),
  })
}
