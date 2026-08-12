/**
 * AuditPage 测试（ADMIN-P0-07 跨租户审计检索）
 * D-联动（板块08 P1-4）：审计展示字段与 teacher-web AuditLogVO 同契约
 * （action/resourceType/detail/userId/tenantId + 平台级 tenantId=null 显示"平台级"）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const mockFetchAuditLogs = vi.fn(() => Promise.resolve([
  {
    auditLogId: 'a-1',
    tenantId: 't-1234',
    userId: 'u-5678',
    action: 'RESET_PASSWORD',
    resourceType: 'user',
    detail: '重置密码',
    createdAt: '2026-07-28T09:00:00Z',
  },
  {
    auditLogId: 'a-2',
    tenantId: null,
    userId: null,
    action: 'DATA_RETENTION_CLEANUP',
    resourceType: 'system',
    detail: '定期清理',
    createdAt: '2026-07-29T03:00:00Z',
  },
]))

vi.mock('../api', () => ({
  fetchAuditLogs: (...args: unknown[]) => mockFetchAuditLogs(...args),
}))

import AuditPage from '../pages/AuditPage'

describe('AuditPage（跨租户审计日志）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染租户级审计记录（动作/操作人/租户/详情）', async () => {
    render(<AuditPage />)
    expect(await screen.findByText('RESET_PASSWORD')).toBeInTheDocument()
    expect(screen.getByText('u-5678')).toBeInTheDocument()
    expect(screen.getByText('t-1234')).toBeInTheDocument()
    expect(screen.getByText('重置密码')).toBeInTheDocument()
  })

  it('系统级审计（tenantId=null）显示"平台级"，不崩溃', async () => {
    render(<AuditPage />)
    await waitFor(() => expect(screen.getByText('DATA_RETENTION_CLEANUP')).toBeInTheDocument())
    expect(screen.getAllByText('平台级').length).toBeGreaterThanOrEqual(1)
  })

  it('加载失败展示错误信息', async () => {
    mockFetchAuditLogs.mockRejectedValueOnce(new Error('网络异常'))
    render(<AuditPage />)
    await waitFor(() => expect(screen.getByText('网络异常')).toBeInTheDocument())
  })
})
