// AUD-007：双 token 移出 localStorage，改存 sessionStorage（会话级，关闭浏览器自动清除）
// 与 student-h5 策略对齐：XSS 单点突破不再获得持久凭证；刷新页面仍保持登录（同 tab 会话）
// DC-005：认证传输三端收敛为共享模块（SPEC §19）——token 存取/刷新/authFetch/登出/错误模型
import { createSessionStorageTokens } from '../../shared/src/auth-transport/tokenStorage'
import { createAuthFetch } from '../../shared/src/auth-transport/authFetch'
import { handleSessionExpired } from '../../shared/src/auth-transport/sessionExpired'
import { toApiError } from '../../shared/src/auth-transport/apiError'

const storage = createSessionStorageTokens('mindsafe_')

export const getToken = () => storage.getToken()

export const setToken = (token: string) => storage.setToken(token)

export const getRefreshToken = () => storage.getRefreshToken()

export const setRefreshToken = (token: string) => storage.setRefreshToken(token)

export const clearToken = () => storage.clear()

/**
 * 带 JWT 认证的 fetch（DC-005：共享实现，自动携带 token + 401 刷新重放，ARCH-008 F-6）
 *
 * 适用于 blob 下载 / multipart 上传等不解析 JSON 的场景；
 * 返回原始 Response，成功/失败由调用方处理。
 * 401 语义：内部尝试刷新并重放一次；仍 401 则原样返回（登出决策交调用方）。
 */
export const authFetch = createAuthFetch(storage)

// ===== DTO 类型（与后端 TeacherService VO record 一一对齐） =====

/** 预警状态 */
export type AlertStatus = 'open' | 'claimed' | 'resolved' | 'false_positive'

export interface DailyCount { date: string; count: number }

export interface DashboardVO {
  pendingAlerts: number
  todayAlerts: number
  todaySessions: number
  activeStudents: number
  totalSessions: number
  weeklyTrend: DailyCount[]
  avgSatisfaction: number
  satisfactionCount: number
}

export interface AlertVO {
  alertId: string
  studentUserId: string
  studentName: string
  riskType: string
  riskLevel: number
  status: AlertStatus
  detectedAt: string
  assignedUserId: string | null
  mutedFromTodo: boolean
}

export interface SessionSummaryVO {
  sessionId: string
  startedAt: string
  status: string
  riskLevel: number
  satisfactionRating: number | null
}

export interface NoteVO {
  noteId: string
  teacherUserId: string
  content: string
  noteType: string
  createdAt: string
}

export interface StudentProfileVO {
  studentUserId: string
  displayName: string
  gradeCode: string
  classCode: string
  maxRiskLevel: number | null
  totalSessions: number
  recentSessions: SessionSummaryVO[]
  alertHistory: AlertVO[]
  notes: NoteVO[]
}

export interface HighRiskStudentVO {
  studentUserId: string
  displayName: string
  gradeCode: string
  maxRiskLevel: number
  openAlertCount: number
  lastAlertAt: string
}

export interface StudentVO {
  userId: string
  displayName: string
  gradeCode: string
  classCode: string
}

export interface MessageSummaryVO {
  summaryId: string
  senderType: string
  turnCount: number
  contentSummary: string
  emotionLabel: string
  riskLevel: number
  createdAt: string
}

export interface RiskDistItem { level: number; label: string; count: number }
export interface ClassRiskItem { classCode: string; alertCount: number; studentCount: number }
export interface EmotionItem { emotion: string; count: number }

export interface StatsVO {
  riskDistribution: RiskDistItem[]
  classComparison: ClassRiskItem[]
  sessionTrend: DailyCount[]
  emotionDistribution: EmotionItem[]
}

export interface RatingDistItem { stars: number; count: number }

export interface SatisfactionStatsVO {
  totalRated: number
  avgRating: number
  distribution: RatingDistItem[]
  recentCount: number
  recentAvg: number
}

/** DATA-004 待回访条目（后端 LinkedHashMap 输出） */
export interface FollowUpItem {
  riskEventId: string
  studentUserId: string
  riskType: string
  riskLevel: number
  followUpAt: string
  resolutionNote: string
  detectedAt: string
}

/** 会话 AI 摘要（后端 Map 输出） */
export interface SessionAiSummary { summary: string; status: 'ready' | 'pending' | 'not_found' }

export async function api<T = any>(path: string, options: RequestInit & { headers?: Record<string, string> } = {}): Promise<T> {
  let res: Response
  try {
    res = await authFetch(`/api/v1${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...options.headers,
      },
    })
  } catch {
    // 网络层失败（后端未启动 / 代理不可达）
    throw new Error('后端服务不可达，请确认服务已启动')
  }
  if (res.status === 401) {
    // authFetch 已尝试刷新+重放；仍 401 → 刷新失败 → 统一登出决策点（clear + reload + throw）
    handleSessionExpired(storage)
  }
  const json = await res.json()
  if (!json.success) {
    throw toApiError(json)
  }
  return json.data
}

// ===== 工作台 =====
export const getDashboard = (): Promise<DashboardVO> => api('/teacher/dashboard')
export const getStats = (): Promise<StatsVO> => api('/teacher/stats')
export const getSatisfaction = (): Promise<SatisfactionStatsVO> => api('/teacher/satisfaction')

// ===== 平台管理 =====
export const getPlatformOverview = () => api('/platform/overview')
export const getPlatformTenants = () => api('/platform/tenants')

// ===== 质量监控 =====
export const getQualityStats = () => api('/teacher/quality/stats')
export const getFlaggedSessions = () => api('/teacher/quality/flagged')

/**
 * AUD-018：blob 下载统一封装（收敛 5 处重复的 401 处理 + 下载逻辑）
 * - 401 → 统一登出决策点（clear + reload，与 authFetch 语义一致）
 * - 统一 <a download> 触发下载：window.open 在 async 之后调用会失去用户激活被弹窗拦截
 */
async function downloadBlob(path: string, filename: string) {
  const res = await authFetch(`/api/v1${path}`)
  if (res.status === 401) { handleSessionExpired(storage) }
  const blob = await res.blob()
  const a = document.createElement('a')
  const url = URL.createObjectURL(blob)
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export const exportSessionPdf = (sessionId: string) =>
  downloadBlob(`/teacher/sessions/${sessionId}/export`, `session_${sessionId.slice(0, 8)}.pdf`)

// ===== 预警队列 =====
export const getAlerts = (params: { status?: AlertStatus; minLevel?: number; limit?: number } = {}): Promise<AlertVO[]> => {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.minLevel != null) qs.set('minLevel', String(params.minLevel))
  if (params.limit) qs.set('limit', String(params.limit))
  const query = qs.toString()
  return api(`/alerts${query ? '?' + query : ''}`)
}
export const claimAlert = (id: string) => api(`/alerts/${id}/claim`, { method: 'POST' })
export const markFalsePositive = (id: string) => api(`/alerts/${id}/false-positive`, { method: 'PATCH' })
export const getPendingFollowups = (): Promise<FollowUpItem[]> => api('/alerts/pending-followups')
export const resolveAlert = (id: string, resolutionNote?: string) =>
  api(`/alerts/${id}/resolve`, {
    method: 'POST',
    body: JSON.stringify({ resolutionNote }),
  })

// ===== 学生管理 =====
export const getStudents = (): Promise<StudentVO[]> => api('/teacher/students')
export const getHighRiskStudents = (): Promise<HighRiskStudentVO[]> => api('/teacher/students/high-risk')
export const getStudentProfile = (id: string): Promise<StudentProfileVO> => api(`/teacher/students/${id}`)
/** 后端返回 Map（6 维度 + 里程碑），结构见 ProfileRadarService */
export const getStudentRadar = (id: string): Promise<Record<string, any>> => api(`/teacher/students/${id}/radar`)
export const addStudentNote = (id: string, content: string, noteType = 'general') =>
  api(`/teacher/students/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({ content, noteType }),
  })

// ===== 通知 =====
export const getNotifications = (limit = 50) => api(`/teacher/notifications?limit=${limit}`)
/** 未读数量（后端 Long） */
export const getUnreadCount = (): Promise<number> => api('/teacher/notifications/unread-count')
export const markNotificationRead = (id: string) => api(`/teacher/notifications/${id}/read`, { method: 'PUT' })

// ===== 对话摘要 =====
export const getSessionMessages = (sessionId: string): Promise<MessageSummaryVO[]> => api(`/teacher/sessions/${sessionId}/messages`)
export const getSessionSummary = (sessionId: string): Promise<SessionAiSummary> => api(`/teacher/sessions/${sessionId}/summary`)
export const takeoverSession = (sessionId: string): Promise<Record<string, any>> => api(`/teacher/sessions/${sessionId}/takeover`, { method: 'POST' })

// ===== 管理控制台（admin） =====
export const getInviteCodes = () => api('/admin/invite-codes')
export const createInviteCode = (maxUses: number, expireDays: number) =>
  api('/admin/invite-codes', {
    method: 'POST',
    body: JSON.stringify({ maxUses, expireDays }),
  })
export const deactivateInviteCode = (codeId: string) =>
  api(`/admin/invite-codes/${codeId}/deactivate`, { method: 'PATCH' })
export const deleteInviteCode = (codeId: string) =>
  api(`/admin/invite-codes/${codeId}`, { method: 'DELETE' })

// ===== 数据导出（CSV/PDF 下载，AUD-018 收敛至 downloadBlob） =====
export const openWeeklyReport = () => downloadBlob('/teacher/report/weekly', 'weekly_report.pdf')
export const exportAlertsCsv = () => downloadBlob('/teacher/export/alerts', 'alerts_export.csv')
export const exportStudentsCsv = () => downloadBlob('/teacher/export/students', 'students_export.csv')

// ===== 批量导入（admin） =====
export const downloadImportTemplate = () =>
  downloadBlob('/admin/invite-codes/import-template', 'student_import_template.csv')

export async function importStudentsCsv(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  // authFetch 已内置 401 刷新+重放；仍 401 → 刷新失败 → 登出
  const res = await authFetch('/api/v1/admin/invite-codes/import-students', {
    method: 'POST',
    body: formData,
  })
  if (res.status === 401) {
    // 统一登出决策点
    handleSessionExpired(storage)
  }
  const json = await res.json()
  if (!json.success) throw toApiError({ code: json.code, message: json.message || '导入失败' })
  return json.data
}

// ===== 审计日志（admin） =====
export const getAuditLogs = (action?: string) => {
  const qs = action ? `?action=${action}` : ''
  return api(`/admin/invite-codes/audit-logs${qs}`)
}
