/**
 * endpoints 端点单一事实源测试（板块08 P1-1）
 * 覆盖：fillPath 路径参数替换（含缺参空串）、FRONTEND_ENDPOINTS 派生（占位符剥离）、
 * 常量表关键端点登记完整性。
 */
import { describe, it, expect } from 'vitest'
import { ENDPOINTS, fillPath, FRONTEND_ENDPOINTS } from '../api/endpoints'

describe('endpoints 端点常量表', () => {
  it('fillPath：替换路径参数', () => {
    expect(fillPath('/api/v1/platform/config/{key}', { key: 'chat.maxTurns' }))
      .toBe('/api/v1/platform/config/chat.maxTurns')
  })

  it('fillPath：缺参按空串（测试期望语义）', () => {
    expect(fillPath('/api/v1/platform/config/{key}', {})).toBe('/api/v1/platform/config/')
  })

  it('fillPath：无占位符模板原样返回', () => {
    expect(fillPath('/api/v1/ops/services/status', {})).toBe('/api/v1/ops/services/status')
  })

  it('FRONTEND_ENDPOINTS：占位符剥离且全部以 /api/v1 开头', () => {
    expect(FRONTEND_ENDPOINTS.length).toBeGreaterThan(10)
    for (const [path, method] of FRONTEND_ENDPOINTS) {
      expect(path).toMatch(/^\/api\/v1/)
      expect(['get', 'post']).toContain(method)
    }
  })

  it('关键端点登记完整（认证/总览/服务/审计）', () => {
    expect(ENDPOINTS.platformLogin.path).toBe('/api/v1/platform/auth/login')
    expect(ENDPOINTS.platformOverview.path).toBe('/api/v1/platform/overview')
    expect(ENDPOINTS.servicesStatus.path).toBe('/api/v1/ops/services/status')
    expect(ENDPOINTS.auditLogs.path).toBe('/api/v1/ops/audit-logs')
    expect(ENDPOINTS.platformLogin.method).toBe('post')
  })
})
