const TOKEN_KEY = 'mindsafe_token'
const REFRESH_KEY = 'mindsafe_refresh'

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
}

/** 尝试刷新 Token */
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

export async function api(path: string, options: RequestInit & { headers?: Record<string, string> } = {}): Promise<any> {
  const token = getToken()
  let res: Response
  try {
    res = await fetch(`/api/v1${path}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
    })
  } catch (e) {
    // 网络层失败（后端未启动 / 代理不可达）
    throw new Error('后端服务不可达，请确认服务已启动')
  }
  if (res.status === 401) {
    if (await tryRefresh()) {
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
    throw new Error('登录已过期')
  }
  const json = await res.json()
  if (!json.success) {
    throw new Error(json.message || '请求失败')
  }
  return json.data
}

// ===== 工作台 =====
export const getDashboard = () => api('/teacher/dashboard')
export const getStats = () => api('/teacher/stats')
export const getSatisfaction = () => api('/teacher/satisfaction')

// ===== 平台管理 =====
export const getPlatformOverview = () => api('/platform/overview')
export const getPlatformTenants = () => api('/platform/tenants')
export const getPlatformTenantDetail = (id) => api(`/platform/tenants/${id}`)
export const getPlatformSchools = () => api('/platform/schools')

// ===== 质量监控 =====
export const getQualityStats = () => api('/teacher/quality/stats')
export const getFlaggedSessions = () => api('/teacher/quality/flagged')
export const getTemplates = () => api('/teacher/templates')
export const exportSessionPdf = (sessionId: string) => {
  const token = getToken()
  return fetch(`/api/v1/teacher/sessions/${sessionId}/export`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then(r => r.blob()).then(blob => {
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
  })
}

// ===== 预警队列 =====
export const getAlerts = (params: { status?: string; minLevel?: number; limit?: number } = {}) => {
  const qs = new URLSearchParams()
  if (params.status) qs.set('status', params.status)
  if (params.minLevel != null) qs.set('minLevel', String(params.minLevel))
  if (params.limit) qs.set('limit', String(params.limit))
  const query = qs.toString()
  return api(`/alerts${query ? '?' + query : ''}`)
}
export const claimAlert = (id) => api(`/alerts/${id}/claim`, { method: 'POST' })
export const markFalsePositive = (id) => api(`/alerts/${id}/false-positive`, { method: 'PATCH' })
export const getPendingFollowups = () => api('/alerts/pending-followups')
export const resolveAlert = (id, resolutionNote) =>
  api(`/alerts/${id}/resolve`, {
    method: 'POST',
    body: JSON.stringify({ resolutionNote }),
  })

// ===== 学生管理 =====
export const getStudents = () => api('/teacher/students')
export const getHighRiskStudents = () => api('/teacher/students/high-risk')
export const getStudentProfile = (id) => api(`/teacher/students/${id}`)
export const getStudentRadar = (id) => api(`/teacher/students/${id}/radar`)
export const addStudentNote = (id, content, noteType = 'general') =>
  api(`/teacher/students/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({ content, noteType }),
  })
export const generateParentLink = (studentId) =>
  api(`/teacher/students/${studentId}/parent-link`, { method: 'POST' })

// ===== 通知 =====
export const getNotifications = (limit = 50) => api(`/teacher/notifications?limit=${limit}`)
export const getUnreadCount = () => api('/teacher/notifications/unread-count')
export const markNotificationRead = (id) => api(`/teacher/notifications/${id}/read`, { method: 'PUT' })

// ===== 对话摘要 =====
export const getSessionMessages = (sessionId) => api(`/teacher/sessions/${sessionId}/messages`)
export const getSessionSummary = (sessionId) => api(`/teacher/sessions/${sessionId}/summary`)
export const takeoverSession = (sessionId) => api(`/teacher/sessions/${sessionId}/takeover`, { method: 'POST' })

// ===== 管理控制台（admin） =====
export const getInviteCodes = () => api('/admin/invite-codes')
export const createInviteCode = (maxUses, expireDays) =>
  api('/admin/invite-codes', {
    method: 'POST',
    body: JSON.stringify({ maxUses, expireDays }),
  })
export const deactivateInviteCode = (codeId) =>
  api(`/admin/invite-codes/${codeId}/deactivate`, { method: 'PATCH' })
export const deleteInviteCode = (codeId) =>
  api(`/admin/invite-codes/${codeId}`, { method: 'DELETE' })

// ===== 数据导出（CSV 下载） =====
export function openWeeklyReport() {
  const token = getToken()
  fetch('/api/v1/teacher/report/weekly', {
    headers: { Authorization: `Bearer ${token}` },
  }).then(r => r.blob()).then(blob => {
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
  })
}

export function exportAlertsCsv() {
  const token = getToken()
  const a = document.createElement('a')
  fetch('/api/v1/teacher/export/alerts', {
    headers: { Authorization: `Bearer ${token}` },
  }).then(r => r.blob()).then(blob => {
    const url = URL.createObjectURL(blob)
    a.href = url
    a.download = 'alerts_export.csv'
    a.click()
    URL.revokeObjectURL(url)
  })
}

export function exportStudentsCsv() {
  const token = getToken()
  const a = document.createElement('a')
  fetch('/api/v1/teacher/export/students', {
    headers: { Authorization: `Bearer ${token}` },
  }).then(r => r.blob()).then(blob => {
    const url = URL.createObjectURL(blob)
    a.href = url
    a.download = 'students_export.csv'
    a.click()
    URL.revokeObjectURL(url)
  })
}

// ===== 批量导入（admin） =====
export function downloadImportTemplate() {
  const token = getToken()
  const a = document.createElement('a')
  fetch('/api/v1/admin/invite-codes/import-template', {
    headers: { Authorization: `Bearer ${token}` },
  }).then(r => r.blob()).then(blob => {
    const url = URL.createObjectURL(blob)
    a.href = url
    a.download = 'student_import_template.csv'
    a.click()
    URL.revokeObjectURL(url)
  })
}

export async function importStudentsCsv(file) {
  const token = getToken()
  const formData = new FormData()
  formData.append('file', file)
  const res = await fetch('/api/v1/admin/invite-codes/import-students', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: formData,
  })
  if (res.status === 401) {
    clearToken()
    window.location.reload()
    throw new Error('登录已过期')
  }
  const json = await res.json()
  if (!json.success) throw new Error(json.message || '导入失败')
  return json.data
}

// ===== 审计日志（admin） =====
export const getAuditLogs = (action?: string) => {
  const qs = action ? `?action=${action}` : ''
  return api(`/admin/invite-codes/audit-logs${qs}`)
}

// ===== 风险事件（M1 兼容） =====
export const getRiskEvents = (limit = 50) => api(`/teacher/risk-events?limit=${limit}`)
