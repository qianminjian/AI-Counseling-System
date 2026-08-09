import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import SlaPage from '../pages/SlaPage'

vi.mock('../api', () => ({
  fetchSlaStats: vi.fn(),
}))

import { fetchSlaStats } from '../api'

describe('SlaPage 时效监控', () => {
  beforeEach(() => {
    vi.mocked(fetchSlaStats).mockReset()
  })

  it('渲染 SLA 达标率/逾期/P95（按等级行）', async () => {
    vi.mocked(fetchSlaStats).mockResolvedValue([
      { riskLevel: 3, total: 10, onTime: 8, onTimeRate: 80, p95Minutes: 20 },
      { riskLevel: 2, total: 5, onTime: 5, onTimeRate: 100, p95Minutes: 30 },
    ])

    render(<SlaPage />)

    await waitFor(() => expect(screen.getByText('RED')).toBeInTheDocument())
    expect(screen.getByText('ORANGE')).toBeInTheDocument()
    expect(screen.getAllByText(/80%/).length).toBeGreaterThan(0)
  })
})
