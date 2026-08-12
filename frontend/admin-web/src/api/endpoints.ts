/**
 * admin-web 前端端点常量表（板块08 P1-1，对齐 teacher-web FA-15 / student-h5 R-001 单一事实源）
 *
 * 此前 32+ 处 /api/v1 路径散落在 api.ts 内（含 8 处裸 fetch），新增端点无编译期约束，
 * R-001 三端端点治理唯独漏接本端。现在：
 * - adminFetch / postAdmin / platformLogin 全部消费本表，路径只在本文件出现一次
 * - path 为全路径模板（/api/v1 开头），路径参数用 {name} 占位（fillPath 替换）
 * - method 一并登记
 * - FRONTEND_ENDPOINTS 由本表派生（占位符剥离），adminWebContract 测试直接校验常量表
 * - 查询参数不在本表（query 由调用方拼接在 path 尾部），与 teacher-web 语义一致
 * - 只收敛路径定义，不改变任何请求行为（DEC-007 独立登录态语义保持不变）
 */
export const ENDPOINTS = {
  // 认证（DEC-007：独立平台登录端点，PLATFORM_ token 前缀由后端签发）
  platformLogin: { path: '/api/v1/platform/auth/login', method: 'post' },
  // 平台总览 / 租户 / 服务状态（P0 backlog ⑤ 双轨收敛迁入）
  platformOverview: { path: '/api/v1/platform/overview', method: 'get' },
  platformTenants: { path: '/api/v1/platform/tenants', method: 'get' },
  servicesStatus: { path: '/api/v1/ops/services/status', method: 'get' },
  // 配置注册表（ADMIN-P1-01 + BUG-A-03-01 配置变更历史）
  configRegistry: { path: '/api/v1/platform/config/registry', method: 'get' },
  updateConfig: { path: '/api/v1/platform/config/{key}', method: 'post' },
  configHistory: { path: '/api/v1/platform/config/{key}/history', method: 'get' },
  // 风险全景（ADMIN-P1-04）
  riskOverview: { path: '/api/v1/ops/risk/overview', method: 'get' },
  riskOverdue: { path: '/api/v1/ops/risk/overdue', method: 'get' },
  slaStats: { path: '/api/v1/ops/risk/sla-stats', method: 'get' },
  // 降级矩阵（ADMIN-P2-01/02）
  degradationMatrix: { path: '/api/v1/ops/degradation/matrix', method: 'get' },
  degradationEvents: { path: '/api/v1/ops/degradation/events', method: 'get' },
  degradationOverride: { path: '/api/v1/ops/degradation/{point}/override', method: 'post' },
  cancelDegradationOverride: { path: '/api/v1/ops/degradation/{point}/override/cancel', method: 'post' },
  // Prompt 管理（M7：版本列表 + 提交审核/审核/激活）
  promptAction: { path: '/api/v1/admin/prompts', method: 'post' }, // 操作子路径由调用方拼接在 path 尾部
  promptVersions: { path: '/api/v1/admin/prompts/versions', method: 'get' },
  // 洞察 / 知识库 / 用量 / 合规 / 渠道
  deadLedger: { path: '/api/v1/ops/insights/dead-ledger', method: 'get' },
  knowledgeStats: { path: '/api/v1/ops/knowledge/stats', method: 'get' },
  qualityTrend: { path: '/api/v1/ops/insights/quality-trend', method: 'get' },
  alertFunnel: { path: '/api/v1/ops/insights/alert-funnel', method: 'get' },
  tenantHealth: { path: '/api/v1/ops/insights/tenant-health', method: 'get' },
  usageSummary: { path: '/api/v1/ops/usage/summary', method: 'get' },
  consentStats: { path: '/api/v1/ops/compliance/consent-stats', method: 'get' },
  channelStats: { path: '/api/v1/ops/insights/channel-stats', method: 'get' },
  // 指标看板 + 告警中心（ADMIN-P1-07/08/09）
  metricsQuery: { path: '/api/v1/ops/metrics/query', method: 'get' },
  alertEvents: { path: '/api/v1/ops/alert-events', method: 'get' },
  ackAlertEvent: { path: '/api/v1/ops/alerts/{eventId}/ack', method: 'post' },
  // M13 无屏终端设备管理（CFG-008，doing/84 §六.2 平台管理域）
  platformDevices: { path: '/api/v1/platform/devices', method: 'get' },
  platformDeviceDetail: { path: '/api/v1/platform/devices/{deviceId}', method: 'get' },
  exportDeviceQr: { path: '/api/v1/platform/devices/export-qr', method: 'post' },
  batchDeviceOperation: { path: '/api/v1/platform/devices/batch', method: 'post' },
  // 跨租户审计（ADMIN-P0-07）
  auditLogs: { path: '/api/v1/ops/audit-logs', method: 'get' },
} as const

export type EndpointKey = keyof typeof ENDPOINTS

/** 路径参数替换：'/api/v1/.../{key}' + { key: 'x' } → '/api/v1/.../x'；未提供参数按空串（测试期望） */
export function fillPath(template: string, params: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => params[name] ?? '')
}

/**
 * 契约清单（P1-1：从常量表派生，占位符剥离，供 adminWebContract 测试直接校验）
 * 新增端点只需登记 ENDPOINTS 一处，本清单自动跟随
 */
export const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = Object.values(ENDPOINTS).map((e) => [
  e.path.replace(/\{\w+\}/g, '').replace(/\/+$/, ''),
  e.method,
])
