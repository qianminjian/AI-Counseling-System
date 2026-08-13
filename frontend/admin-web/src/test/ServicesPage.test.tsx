/**
 * ServicesPage 测试（ADMIN-P0-05：六服务实时健康三态）
 * 覆盖：UP/DEGRADED/DOWN 三种 Tag 渲染、加载失败错误提示。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const mockFetchServicesStatus = vi.fn(() => Promise.resolve({
  backend: 'UP', 'tts-service': 'DEGRADED', 'voice-service': 'DOWN',
}))

vi.mock('../api', () => ({
  fetchServicesStatus: (...args: unknown[]) => mockFetchServicesStatus(...args),
}))

import ServicesPage from '../pages/ServicesPage'

describe('ServicesPage（服务状态）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染六服务健康三态（UP/DEGRADED/DOWN）', async () => {
    render(<ServicesPage />)
    expect(await screen.findByText('backend')).toBeInTheDocument()
    expect(screen.getByText('UP')).toBeInTheDocument()
    expect(screen.getByText('DEGRADED')).toBeInTheDocument()
    expect(screen.getByText('DOWN')).toBeInTheDocument()
  })

  it('未知状态渲染红色 Tag（原样显示）', async () => {
    mockFetchServicesStatus.mockResolvedValueOnce({ ai: 'UNKNOWN' })
    render(<ServicesPage />)
    expect(await screen.findByText('UNKNOWN')).toBeInTheDocument()
  })

  it('加载失败展示错误信息', async () => {
    mockFetchServicesStatus.mockRejectedValueOnce(new Error('服务不可达'))
    render(<ServicesPage />)
    await waitFor(() => expect(screen.getByText('服务不可达')).toBeInTheDocument())
  })
})
