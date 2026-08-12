/**
 * doing/73 T3（AC-5）：parent-h5 业务 API 服务层
 * 底层 = platform/request.ts 工厂（Bearer 注入 / 401 刷新重放 / 统一登出），
 * 存储 = createPlatformTokens('parent_', sessionStorageImpl)（AUD-007 会话级语义保持）
 * 由 src/api/index.ts 迁移而来，导出签名与类型完全等价（页面层零感知）
 * P1 小程序端：仅替换 createPlatformRequest 依赖注入，本文件零改动
 */
import { createPlatformRequest } from '../platform/request'
import { createPlatformTokens } from '../../../shared/src/auth-transport/tokenStorage'
import { sessionStorageImpl } from '../platform/storage'

const request = createPlatformRequest({
  storage: createPlatformTokens('parent_', sessionStorageImpl),
})

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
  /** OBS-P-03-01（2026-08-12）：周报日期范围起始（ISO，近 7 天） */
  weekStart?: string
  /** OBS-P-03-01：AI 建议（后端规则化生成） */
  aiAdvice?: string
}

/** 获取情绪周报（指定学生） */
export function getReport(studentUserId: string) {
  return request<ReportData>(`/parent/report?studentUserId=${studentUserId}`)
}

/** 监护人授权状态（BUG-P-P04-01：同意管理页展示用） */
export interface ConsentStatusData {
  status: 'active' | 'withdrawn'
  consentVersion?: string | null
  consentedAt?: string | null
  withdrawnAt?: string | null
  studentNickname?: string
}

/** 查询监护人授权状态（同意撤回后仍可查询，用于展示"已撤回"） */
export function getConsentStatus(studentUserId: string) {
  return request<ConsentStatusData>(`/parent/consent/status?studentUserId=${studentUserId}`)
}

/** 撤回同意 */
export function withdrawConsent(studentUserId: string) {
  return request<{ message?: string }>('/parent/consent/withdraw', {
    method: 'POST',
    data: { studentUserId }
  })
}
