import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import InsightsPage from '../pages/InsightsPage'

vi.mock('../api', () => ({
  fetchAlertFunnel: vi.fn(),
  fetchQualityTrend: vi.fn(),
  fetchTenantHealth: vi.fn(),
}))

import { fetchAlertFunnel, fetchQualityTrend, fetchTenantHealth } from '../api'

describe('InsightsPage 运营洞察', () => {
  beforeEach(() => {
    vi.mocked(fetchAlertFunnel).mockReset()
    vi.mocked(fetchQualityTrend).mockReset()
    vi.mocked(fetchTenantHealth).mockReset()
  })

  it('渲染预警漏斗 + 质量趋势 + 租户健康度', async () => {
    vi.mocked(fetchAlertFunnel).mockResolvedValue({ detected: 100, notified: 80, claimed: 60 })
    vi.mocked(fetchQualityTrend).mockResolvedValue({})
    vi.mocked(fetchTenantHealth).mockResolvedValue([
      { tenantId: 't1', total: 10, unhandled: 2, overdue: 1, health: 'yellow' },
    ])

    render(<InsightsPage />)

    await waitFor(() => expect(screen.getByText('检出')).toBeInTheDocument())
    expect(screen.getByText('100')).toBeInTheDocument()
    expect(screen.getByText('t1')).toBeInTheDocument()
  })
})
