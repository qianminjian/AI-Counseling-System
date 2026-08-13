/**
 * teacher-web 前端端点常量表（FA-15，单一事实源）
 *
 * 此前端点路径三处镜像：api.ts 函数内硬编码 + 本清单 + apiContract 正则扫源码断言，
 * 新增端点必双改且模板拼路径形态漏检。现在：
 * - api()/callEndpoint()/downloadBlob 全部消费本表，路径只在本文件出现一次
 * - path 为全路径模板（/api/v1 开头），路径参数用 {name} 占位（fillPath 替换）
 * - method 一并登记，callEndpoint 免去调用方重复写 method
 * - FRONTEND_ENDPOINTS 由本表派生（占位符剥离），apiContract 测试直接校验常量表
 */
export const ENDPOINTS = {
  // 认证
  getWecomAuthUrl: { path: '/api/v1/auth/wecom/auth-url', method: 'get' },
  login: { path: '/api/v1/auth/login', method: 'post' },
  changePassword: { path: '/api/v1/auth/change-password', method: 'post' },
  authRefresh: { path: '/api/v1/auth/refresh', method: 'post' }, // 消费在 shared authFetch，清单登记供测试知晓
  // 工作台
  getDashboard: { path: '/api/v1/teacher/dashboard', method: 'get' },
  getStats: { path: '/api/v1/teacher/stats', method: 'get' },
  getSatisfaction: { path: '/api/v1/teacher/satisfaction', method: 'get' },
  // 平台管理
  // 质量监控
  getQualityStats: { path: '/api/v1/teacher/quality/stats', method: 'get' },
  getFlaggedSessions: { path: '/api/v1/teacher/quality/flagged', method: 'get' },
  exportSessionPdf: { path: '/api/v1/teacher/sessions/{sessionId}/export', method: 'get' },
  // 预警队列
  getAlerts: { path: '/api/v1/alerts', method: 'get' },
  claimAlert: { path: '/api/v1/alerts/{id}/claim', method: 'post' },
  markFalsePositive: { path: '/api/v1/alerts/{id}/false-positive', method: 'patch' },
  getPendingFollowups: { path: '/api/v1/alerts/pending-followups', method: 'get' },
  resolveAlert: { path: '/api/v1/alerts/{id}/resolve', method: 'post' },
  alertTemplates: { path: '/api/v1/teacher/templates', method: 'get' },
  // 学生管理
  getStudents: { path: '/api/v1/teacher/students', method: 'get' },
  getHighRiskStudents: { path: '/api/v1/teacher/students/high-risk', method: 'get' },
  getStudentProfile: { path: '/api/v1/teacher/students/{id}', method: 'get' },
  getStudentRadar: { path: '/api/v1/teacher/students/{id}/radar', method: 'get' },
  addStudentNote: { path: '/api/v1/teacher/students/{id}/notes', method: 'post' },
  // 通知
  getNotifications: { path: '/api/v1/teacher/notifications', method: 'get' },
  getUnreadCount: { path: '/api/v1/teacher/notifications/unread-count', method: 'get' },
  markNotificationRead: { path: '/api/v1/teacher/notifications/{id}/read', method: 'put' },
  // 对话摘要
  getSessionMessages: { path: '/api/v1/teacher/sessions/{sessionId}/messages', method: 'get' },
  getSessionSummary: { path: '/api/v1/teacher/sessions/{sessionId}/summary', method: 'get' },
  takeoverSession: { path: '/api/v1/teacher/sessions/{sessionId}/takeover', method: 'post' },
  // 管理控制台（admin）
  getInviteCodes: { path: '/api/v1/admin/invite-codes', method: 'get' },
  createInviteCode: { path: '/api/v1/admin/invite-codes', method: 'post' },
  deactivateInviteCode: { path: '/api/v1/admin/invite-codes/{codeId}/deactivate', method: 'patch' },
  deleteInviteCode: { path: '/api/v1/admin/invite-codes/{codeId}', method: 'delete' },
  getAuditLogs: { path: '/api/v1/admin/audit-logs', method: 'get' }, // doing/92 R-002：审计日志独立端点（旧 invite-codes 路径为兼容别名）
  // CFG-001/004/006/008（doing/84 §六.2）：无屏终端设备管理（toB 老师端）
  getDeviceList: { path: '/api/v1/device/list', method: 'get' },
  createBindCode: { path: '/api/v1/device/{deviceCode}/bind-code', method: 'post' },
  bindDevice: { path: '/api/v1/device/{deviceCode}/bind', method: 'post' },
  createVoiceprintTask: { path: '/api/v1/device/{deviceCode}/voiceprint/tasks', method: 'post' },
  getVoiceprintTask: { path: '/api/v1/device/{deviceCode}/voiceprint/tasks/{taskId}', method: 'get' },
  // 数据导出 / 批量导入
  openWeeklyReport: { path: '/api/v1/teacher/report/weekly', method: 'get' },
  exportAlertsCsv: { path: '/api/v1/teacher/export/alerts', method: 'get' },
  exportStudentsCsv: { path: '/api/v1/teacher/export/students', method: 'get' },
  downloadImportTemplate: { path: '/api/v1/admin/invite-codes/import-template', method: 'get' },
  importStudents: { path: '/api/v1/admin/invite-codes/import-students', method: 'post' },
} as const

export type EndpointKey = keyof typeof ENDPOINTS

/** 路径参数替换：'/api/v1/.../{id}' + { id: 'x' } → '/api/v1/.../x'；未提供参数按空串（测试期望） */
export function fillPath(template: string, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? '')
}

/**
 * 契约清单（FA-15：从常量表派生，占位符剥离，供 apiContract 测试直接校验）
 * 新增端点只需登记 ENDPOINTS 一处，本清单自动跟随
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = Object.values(ENDPOINTS).map((e) => [
  e.path.replace(/\{\w+\}/g, '').replace(/\/+$/, ''),
  e.method,
])
