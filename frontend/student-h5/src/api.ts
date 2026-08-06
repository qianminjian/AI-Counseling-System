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
/**
 * 同意键单点（F-9，ARCH-005）：告知同意 / 语音授权 / 语音通话授权
 * 各组件（App/ConsentDialog/VoiceConsentDialog/VoiceCallConsentDialog）只引用枚举，不再各自定义字符串。
 */
export const ConsentKeys = {
  NOTICE: 'mindsafe_consent_v1',
  VOICE: 'mindsafe_voice_consent_v1',
  VOICE_CALL: 'mindsafe_voicecall_consent_v1',
} as const

/** 旧版告知同意键（mindsafe_consent_done），一次性迁移兼容读取 */
const LEGACY_NOTICE_KEY = 'mindsafe_consent_done'

/** 设备是否已完成告知同意（跨 tab 保持；旧键存在时自动迁移到新键） */
export function isConsentDone() {
  if (localStorage.getItem(ConsentKeys.NOTICE) === '1') return true
  if (localStorage.getItem(LEGACY_NOTICE_KEY) === '1') {
    localStorage.setItem(ConsentKeys.NOTICE, '1')
    return true
  }
  return false
}

export function markConsentDone() {
  localStorage.setItem(ConsentKeys.NOTICE, '1')
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

/**
 * 暖场（冷场引导）请求（design/28 §2.3，P0-1）
 *
 * 走 authFetch 统一认证接缝：401 自动刷新并重放，不静默失败。
 * 返回原始 Response（SSE 流由调用方解析，不在此解析 JSON）。
 */
export function fetchWarmPrompt(sessionId: string, silenceSeconds: number): Promise<Response> {
  return authFetch(`/api/v1/chat/sessions/${sessionId}/nudge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ silenceSeconds }),
  })
}

/**
 * 系统配置（F-2/F-3，ARCH-005 端点收敛）
 * GET /api/v1/system/config，支持外部 AbortSignal（remote.ts 启动 3s 超时）。
 */
export function fetchSystemConfig(signal?: AbortSignal): Promise<Response> {
  return authFetch('/api/v1/system/config', {
    headers: { Accept: 'application/json' },
    signal,
  })
}

/** 登录页语音问候语合成（F-2，VoiceLoginOverlay） */
export function fetchLoginPrompt(text: string, persona: string): Promise<Response> {
  return authFetch('/api/v1/tts/login-prompt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, persona }),
  })
}

/** TTS 语音合成（F-2，useTtsPlayer 音频流） */
export function fetchTtsSynthesize(payload: Record<string, unknown>): Promise<Response> {
  return authFetch('/api/v1/tts/synthesize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

/** 语音情感分析（F-2，ChatRoom multipart 上传；不设 Content-Type 由浏览器生成 boundary） */
export function fetchVoiceAnalyze(formData: FormData): Promise<Response> {
  return authFetch('/api/v1/voice/analyze', {
    method: 'POST',
    body: formData,
  })
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
 * 带业务错误码的 API 异常（后端 BizException → HTTP 200 + body.code 约定）。
 * 调用方可按 code 分支处理（如 CONSENT_REQUIRED 20003 → 监护人同意门禁）。
 */
export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** 监护人同意门禁错误码（对齐后端 ErrorCode.CONSENT_REQUIRED） */
export const CONSENT_REQUIRED_CODE = 20003

/** 判断错误是否为监护人同意门禁拦截 */
export function isConsentRequired(err: unknown): boolean {
  return err instanceof ApiError && err.code === CONSENT_REQUIRED_CODE
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
      if (!json.success) throw new ApiError(json.code ?? 0, json.message || '请求失败')
      return json.data
    }
    clearToken()
    window.location.reload()
    throw new Error('登录已过期，请重新进入')
  }

  const json = await res.json()
  if (!json.success) {
    throw new ApiError(json.code ?? 0, json.message || '请求失败')
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
export async function remoteVoiceprintVerify(embeddings: number[][]): Promise<AuthResult & { matched: boolean }> {
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

// ===== 心理工具箱（F-2，design/36 §五 API 契约） =====

/**
 * 后端契约（ToolboxController）：
 * - GET  /toolbox            按年级过滤的工具清单
 * - GET  /toolbox/sos        SOS 场景目标态工具
 * - POST /toolbox/mood-check 练习前后心情记录（toolId/preMood/postMood）
 *
 * reportSosEvent 为 fire-and-forget：SOS 打开上报任何失败（含断网）绝不抛出，
 * 不得阻塞 SOS 界面（design/36 §3.4）。
 */

export interface ToolboxTool {
  toolId: string
  title: string
  emoji: string
  durationSec: number
  minGrade: number
  preMoodCheck: boolean
  postMoodCheck: boolean
  rewardBadge: string | null
  category: string
}

/** 练习前后心情记录结果（对齐后端 ToolboxController 返回：toolId/preMood/postMood/delta/level/needsAttention） */
export interface MoodCheckResult {
  toolId: string
  preMood: number
  postMood: number
  /** 情绪得分差值（post - pre） */
  delta: number
  /** 情绪等级（如 IMPROVED/WORSENED/UNCHANGED） */
  level: string
  /** 恶化需特别关注（前端据此提示） */
  needsAttention: boolean
}

/** 获取当前学生可用工具清单（后端按年级过滤） */
export function fetchToolboxTools(): Promise<ToolboxTool[]> {
  return api('/toolbox')
}

/** 获取 SOS 场景目标态工具（断网时界面仍可静态打开，接口失败由调用方兜底） */
export function fetchSosTools(): Promise<ToolboxTool[]> {
  return api('/toolbox/sos')
}

/** 记录练习前后心情（后端判定效果，恶化时 needsAttention=true） */
export function recordMoodCheck(toolId: string, preMood: number, postMood: number): Promise<MoodCheckResult> {
  return api('/toolbox/mood-check', {
    method: 'POST',
    body: JSON.stringify({ toolId, preMood, postMood }),
  })
}

/**
 * SOS 打开事件上报（fire-and-forget）。
 * 后端 SosController 落 S2 风险事件进教师预警队列（5 分钟去重窗口）。
 * 任何失败静默吞掉——SOS 界面可用性优先于埋点。
 */
export async function reportSosEvent(): Promise<void> {
  try {
    await api('/sos/events', { method: 'POST', body: JSON.stringify({}) })
  } catch {
    // 静默：上报失败绝不阻塞 SOS 界面
  }
}
