/**
 * student-h5 API 工具（JWT 双 Token + 自动刷新）
 * 
 * 共享设备策略：token/user 存 sessionStorage（关闭 tab 自动清除 = 下次必须登录）
 * 设备级标记（如 consent）存 localStorage（跨会话保持）
 */
// AUD-065：consent 读写接入 localStorage 安全封装（隐私模式/存储禁用下不抛 SecurityError）
// doing/94 R-001：ConsentKeys 迁出 utils/consentKeys 独立模块，此处 re-export 保持调用面兼容
export { ConsentKeys, isConsentDone, markConsentDone } from './utils/consentKeys'
// DC-005：认证传输三端收敛为共享模块（SPEC §19）——token 存取/刷新/authFetch/登出/错误模型
import { createSessionStorageTokens } from '../../shared/src/auth-transport/tokenStorage'
import { createAuthFetch } from '../../shared/src/auth-transport/authFetch'
import { handleSessionExpired } from '../../shared/src/auth-transport/sessionExpired'
import { ApiError, toApiError } from '../../shared/src/auth-transport/apiError'
// doing/94 R-001：端点单一事实源（对齐 teacher-web FA-15），路径只登记一次
export { ENDPOINTS, FRONTEND_ENDPOINTS, fillPath } from './endpoints'
import { ENDPOINTS, fillPath } from './endpoints'
export { ApiError }

const storage = createSessionStorageTokens('mindsafe_student_')

export const getToken = () => storage.getToken()

export const setToken = (token: string) => storage.setToken(token)

export const getRefreshToken = () => storage.getRefreshToken()

export const setRefreshToken = (token: string) => storage.setRefreshToken(token)

/** 清双 token + 用户信息（与原 clearToken 语义一致） */
export const clearToken = () => storage.clear()

/** 用户信息键（getUser/setUser 使用；storage.clear() 一并清除 `${prefix}user`） */
const USER_KEY = 'mindsafe_student_user'

export function getUser(): Record<string, unknown> | null {
  try {
    // FE-003：getItem 可能返回 null（JSON.parse(null) 运行时仍安全返回 null，行为不变）
    return JSON.parse(sessionStorage.getItem(USER_KEY)!)
  } catch {
    return null
  }
}

export function setUser(user: Record<string, unknown>) {
  try {
    sessionStorage.setItem(USER_KEY, JSON.stringify(user))
  } catch {
    // F-12：会话存储不可用/配额满 → 静默跳过（与 getUser 失败安全对称，不抛白屏）
  }
}

// ===== 设备级存储（跨会话保持） =====
// doing/94 R-001：ConsentKeys/isConsentDone/markConsentDone 已迁出 utils/consentKeys（见文件头 re-export）

/** 是否已登录（有有效 token） */
export function isAuthenticated() {
  return !!getToken()
}

/**
 * 带 JWT 认证的 fetch（DC-005：共享实现，自动携带 token + 401 刷新重放一次）
 *
 * 适用于不经过 api() 的场景（如 multipart 上传、SSE 流、音频下载），
 * 不解析 JSON、不检查 success 字段——调用方自行处理 Response。
 */
export const authFetch = createAuthFetch(storage)

/**
 * 暖场（冷场引导）请求（design/28 §2.3，P0-1）
 *
 * 走 authFetch 统一认证接缝：401 自动刷新并重放，不静默失败。
 * 返回原始 Response（SSE 流由调用方解析，不在此解析 JSON）。
 */
export function fetchWarmPrompt(sessionId: string, silenceSeconds: number): Promise<Response> {
  return authFetch(fillPath(ENDPOINTS.warmPrompt.path, { sessionId }), {
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
  return authFetch(ENDPOINTS.systemConfig.path, {
    headers: { Accept: 'application/json' },
    signal,
  })
}

/** 登录页语音问候语合成（F-2，VoiceLoginOverlay） */
export function fetchLoginPrompt(text: string, persona: string): Promise<Response> {
  return authFetch(ENDPOINTS.loginPrompt.path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, persona }),
  })
}

/** TTS 语音合成（F-2，useTtsPlayer 音频流） */
export function fetchTtsSynthesize(payload: Record<string, unknown>): Promise<Response> {
  return authFetch(ENDPOINTS.ttsSynthesize.path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

/** 语音情感分析（F-2，ChatRoom multipart 上传；不设 Content-Type 由浏览器生成 boundary） */
export function fetchVoiceAnalyze(formData: FormData): Promise<Response> {
  return authFetch(ENDPOINTS.voiceAnalyze.path, {
    method: 'POST',
    body: formData,
  })
}

/**
 * 带业务错误码的 API 异常（DC-005：共享模块 ApiError，语义不变）。
 * 后端 BizException → HTTP 200 + body.code 约定；
 * 调用方可按 code 分支处理（如 CONSENT_REQUIRED 20003 → 监护人同意门禁）。
 */

/** 监护人同意门禁错误码（对齐后端 ErrorCode.CONSENT_REQUIRED） */
export const CONSENT_REQUIRED_CODE = 20003

/** 判断错误是否为监护人同意门禁拦截 */
export function isConsentRequired(err: unknown): boolean {
  return err instanceof ApiError && err.code === CONSENT_REQUIRED_CODE
}

/**
 * 通用 API 请求（自动携带 JWT + 401 自动刷新；DC-005 认证逻辑在 authFetch 接缝内）
 * 语义：success!==true → toApiError（shared 错误模型）；成功返回 json.data
 */
export async function api(path: string, options: RequestInit & { headers?: Record<string, string> } = {}) {
  // doing/94 R-001：兼容全路径（ENDPOINTS 常量表）与相对路径（组件内联调用）两种形态
  const url = path.startsWith('/api/v1') ? path : `/api/v1${path}`
  const res = await authFetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
  })

  if (res.status === 401) {
    // authFetch 已尝试刷新+重放；仍 401 → 刷新失败 → 统一登出决策点（clear + reload + throw，后续代码不可达）
    handleSessionExpired(storage)
  }

  const json = await res.json()
  // F6 契约（审计）：ApiResponse 统一 {code,message,data,timestamp}，成功=code 0
  if (json.code !== 0) {
    throw toApiError(json)
  }
  return json.data
}

/**
 * 公开端点请求（登录前/无需认证场景，DOC-073 F2，doing/77 §24）
 * 与 api() 同构但不注入 token、不触发 401 刷新；错误契约一处定义（success!==true → toApiError）
 */
async function publicFetch<T = unknown>(path: string, options: RequestInit = {}, fallbackMessage = '请求失败'): Promise<T> {
  // doing/94 R-001：兼容全路径（ENDPOINTS 常量表）与相对路径两种形态
  const url = path.startsWith('/api/v1') ? path : `/api/v1${path}`
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
  })
  const json = await res.json()
  if (json.code !== 0) {
    throw toApiError({ code: json.code, message: json.message || fallbackMessage })
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
  return publicFetch<AuthResult>(ENDPOINTS.trialRegister.path, {
    method: 'POST',
    body: JSON.stringify(data),
  }, '注册失败')
}

/**
 * PIN 码快捷登录（学生用昵称 + 4-6 位数字 PIN）
 */
export async function pinLogin(pseudonym: string, pin: string): Promise<AuthResult> {
  return publicFetch<AuthResult>(ENDPOINTS.pinLogin.path, {
    method: 'POST',
    body: JSON.stringify({ pseudonym, pin }),
  }, '登录失败')
}

/**
 * 设置 PIN 码（注册后引导设置，需已登录）
 */
export async function setPin(pin: string): Promise<void> {
  await api(ENDPOINTS.setPin.path, {
    method: 'POST',
    body: JSON.stringify({ pin }),
  })
}

/**
 * 声纹录入后签发设备登录凭证（需已登录）
 * 凭证与声纹模板一起存本机 IndexedDB，声纹登录时凭其换取正式 token
 */
export async function issueVoiceCredential(): Promise<string> {
  const data = await api(ENDPOINTS.issueVoiceCredential.path, { method: 'POST' })
  return data.voiceCredential
}

/**
 * 声纹登录：本地声纹比对通过后，用设备凭证换取正式双 token
 */
export async function voiceLogin(voiceCredential: string): Promise<AuthResult> {
  return publicFetch<AuthResult>(ENDPOINTS.voiceLogin.path, {
    method: 'POST',
    body: JSON.stringify({ voiceCredential }),
  }, '声纹登录失败')
}

/**
 * 发起监护人同意请求：发送短信验证码到监护人手机（AUTH-040，需已登录）
 */
export async function requestGuardianConsent(guardianPhone: string): Promise<void> {
  await api(ENDPOINTS.requestGuardianConsent.path, {
    method: 'POST',
    body: JSON.stringify({ guardianPhone }),
  })
}

/**
 * 确认监护人同意：校验短信验证码并写入同意记录（AUTH-040，需已登录）
 */
export async function confirmGuardianConsent(guardianPhone: string, code: string): Promise<void> {
  await api(ENDPOINTS.confirmGuardianConsent.path, {
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
  return publicFetch<VoiceprintConfig>(ENDPOINTS.getVoiceprintConfig.path, undefined, '获取声纹配置失败')
}

/**
 * 声纹远程验证登录（remote 模式，公开端点）
 * 前端提取 embedding 后传服务端比对，通过则直接签发双 token
 * <p>
 * AUD-001：必须携带 tenantId——服务端仅在该租户内比对，跨租户模板不可达；
 * tenantId 来源于录入时后端签发（remoteVoiceprintEnroll 响应），本地暂存后在此回传
 */
export async function remoteVoiceprintVerify(embeddings: number[][], tenantId: string): Promise<AuthResult & { matched: boolean }> {
  return publicFetch<AuthResult & { matched: boolean }>(ENDPOINTS.remoteVoiceprintVerify.path, {
    method: 'POST',
    body: JSON.stringify({ tenantId, embeddings }),
  }, '声纹验证失败')
}

/**
 * 声纹远程录入（remote 模式，需已登录）
 * 前端提取 embedding 后传服务端存储
 * 响应携带服务端签发的 tenantId（AUD-001：verify 时需回传租户维度）
 */
export async function remoteVoiceprintEnroll(embeddings: number[][]): Promise<{ enrolled: number; tenantId: string }> {
  return api(ENDPOINTS.remoteVoiceprintEnroll.path, {
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
  return api(ENDPOINTS.toolboxTools.path)
}

/** 记录练习前后心情（后端判定效果，恶化时 needsAttention=true） */
export function recordMoodCheck(toolId: string, preMood: number, postMood: number): Promise<MoodCheckResult> {
  return api(ENDPOINTS.moodCheck.path, {
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
    await api(ENDPOINTS.sosEvents.path, { method: 'POST', body: JSON.stringify({}) })
  } catch {
    // 静默：上报失败绝不阻塞 SOS 界面
  }
}
