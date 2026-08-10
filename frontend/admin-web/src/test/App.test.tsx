import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import App from '../App'

vi.mock('../api', () => ({
  platformLogin: vi.fn(),
  fetchServicesStatus: vi.fn(),
  fetchPlatformOverview: vi.fn(),
  fetchPlatformTenants: vi.fn(),
  adminLogout: vi.fn(),
  getAdminToken: vi.fn(),
  getAdminRole: vi.fn(),
  getAdminName: vi.fn(),
  UNAUTHORIZED_EVENT: 'admin:unauthorized',
}))

import { getAdminRole, getAdminToken, getAdminName, platformLogin, fetchServicesStatus } from '../api'

describe('App 路由守卫', () => {
  beforeEach(() => {
    vi.mocked(platformLogin).mockReset()
    vi.mocked(fetchServicesStatus).mockReset()
    vi.mocked(getAdminToken).mockReset()
    vi.mocked(getAdminRole).mockReset()
    vi.mocked(getAdminName).mockReset()
    sessionStorage.clear()
  })

  it('未登录 → 渲染登录页', () => {
    vi.mocked(getAdminToken).mockReturnValue(null)
    render(<App />)
    expect(screen.getByText('MindSafe 平台管理后台')).toBeInTheDocument()
  })

  it('已登录（ops_admin）→ 渲染主布局与服务状态', async () => {
    vi.mocked(getAdminToken).mockReturnValue('PLATFORM_x')
    vi.mocked(getAdminRole).mockReturnValue('ops_admin')
    vi.mocked(getAdminName).mockReturnValue('运维')
    vi.mocked(fetchServicesStatus).mockResolvedValue({ postgres: 'UP', redis: 'UP', backend: 'UP', tts: 'DEGRADED', voice: 'UP', nginx: 'DOWN' })

    render(<App />)

    await waitFor(() => expect(screen.getByText('运维（ops_admin）')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('DEGRADED')).toBeInTheDocument())
    // ops_admin 无审计菜单
    expect(screen.queryByText('审计日志')).not.toBeInTheDocument()
  })
})
