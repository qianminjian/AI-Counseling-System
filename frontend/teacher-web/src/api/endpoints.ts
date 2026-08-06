/**
 * teacher-web 前端端点清单（ARCH-008 F-7 契约防线，单一事实源）
 *
 * 形态约定（与 apiContract.test.ts 的规范化规则一致）：
 * - 全路径（/api/v1 开头），路径参数占位符剥离（{id} → 无），query 剥离
 * - 方法为 HTTP 方法小写
 * - 新增/修改端点时必须同步本清单，测试断言源码全部 API 路径 ⊆ 本清单
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = [
  // 认证
  ['/api/v1/auth/refresh', 'post'],
  // 工作台
  ['/api/v1/teacher/dashboard', 'get'],
  ['/api/v1/teacher/stats', 'get'],
  ['/api/v1/teacher/satisfaction', 'get'],
  // 平台管理
  ['/api/v1/platform/overview', 'get'],
  ['/api/v1/platform/tenants', 'get'],
  // 质量监控
  ['/api/v1/teacher/quality/stats', 'get'],
  ['/api/v1/teacher/quality/flagged', 'get'],
  ['/api/v1/teacher/sessions/export', 'get'],
  // 预警队列
  ['/api/v1/alerts', 'get'],
  ['/api/v1/alerts/claim', 'post'],
  ['/api/v1/alerts/false-positive', 'patch'],
  ['/api/v1/alerts/pending-followups', 'get'],
  ['/api/v1/alerts/resolve', 'post'],
  // 学生管理
  ['/api/v1/teacher/students', 'get'],
  ['/api/v1/teacher/students/high-risk', 'get'],
  ['/api/v1/teacher/students/radar', 'get'],
  ['/api/v1/teacher/students/notes', 'post'],
  // 通知
  ['/api/v1/teacher/notifications', 'get'],
  ['/api/v1/teacher/notifications/unread-count', 'get'],
  ['/api/v1/teacher/notifications/read', 'put'],
  // 对话摘要
  ['/api/v1/teacher/sessions/messages', 'get'],
  ['/api/v1/teacher/sessions/summary', 'get'],
  ['/api/v1/teacher/sessions/takeover', 'post'],
  // 管理控制台（admin）
  ['/api/v1/admin/invite-codes', 'get'],
  ['/api/v1/admin/invite-codes', 'post'],
  ['/api/v1/admin/invite-codes/deactivate', 'patch'],
  ['/api/v1/admin/invite-codes', 'delete'],
  ['/api/v1/admin/invite-codes/audit-logs', 'get'],
  // 数据导出 / 批量导入
  ['/api/v1/teacher/report/weekly', 'get'],
  ['/api/v1/teacher/export/alerts', 'get'],
  ['/api/v1/teacher/export/students', 'get'],
  ['/api/v1/admin/invite-codes/import-template', 'get'],
  ['/api/v1/admin/invite-codes/import-students', 'post'],
]
