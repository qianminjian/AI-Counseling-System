/**
 * OverviewPage 测试（ADMIN-P0-04/05 + P0 backlog ⑤ 双轨收敛）
 * 覆盖：平台总览指标（租户/学校/学生/教师/会话/预警）、租户列表（状态 Tag）、
 * 服务健康状态（UP/DEGRADED/DOWN 三态）、加载失败错误提示。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const mockFetchServicesStatus = vi.fn(() => Promise.resolve({
  backend: 'UP', 'tts-service': 'DEGRADED', 'voice-service': 'DOWN',
}))
const mockFetchOverview = vi.fn(() => Promise.resolve({
  tenantCount: 3, schoolCount: 5, studentCount: 123, teacherCount: 8,
  totalSessions: 500, totalAlerts: 15, openAlerts: 2,
}))
const mockFetchTenants = vi.fn(() => Promise.resolve([
  { tenantName: '市西小学', status: 'ACTIVE', tenantCode: 'SCH001', schoolCount: 1, studentCount: 120, teacherCount: 8, sessionCount: 300, createdAt: '2026-01-01T00:00:00Z' },
  { tenantName: '育才中学', status: 'SUSPENDED', tenantCode: 'SCH002', schoolCount: 1, studentCount: 80, teacherCount: 5, sessionCount: 200, createdAt: '2026-02-01T00:00:00Z' },
]))

vi.mock('../api', () => ({
  fetchServicesStatus: (...args: unknown[]) => mockFetchServicesStatus(...args),
  fetchPlatformOverview: (...args: unknown[]) => mockFetchOverview(...args),
  fetchPlatformTenants: (...args: unknown[]) => mockFetchTenants(...args),
  getAdminToken: () => 'tok',
  getAdminName: () => 'admin-1',
}))

import OverviewPage from '../pages/OverviewPage'

describe('OverviewPage（平台运营总览）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('平台总览指标：租户/学校/学生/教师/会话/预警展示', async () => {
    render(<OverviewPage />)
    expect(await screen.findByText('租户数')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('123')).toBeInTheDocument()
    expect(screen.getByText('500')).toBeInTheDocument()
  })

  it('租户列表：展示租户名/状态 Tag（启用/停用）', async () => {
    render(<OverviewPage />)
    expect(await screen.findByText('市西小学')).toBeInTheDocument()
    expect(screen.getByText('育才中学')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('停用')).toBeInTheDocument()
  })

  it('服务健康状态：UP/DEGRADED/DOWN 三态展示', async () => {
    render(<OverviewPage />)
    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(screen.getByText('DEGRADED')).toBeInTheDocument()
    expect(screen.getByText('DOWN')).toBeInTheDocument()
  })

  it('加载失败：展示错误信息', async () => {
    mockFetchOverview.mockRejectedValueOnce(new Error('网络异常'))
    render(<OverviewPage />)
    await waitFor(() => expect(screen.getByText('网络异常')).toBeInTheDocument())
  })
})
