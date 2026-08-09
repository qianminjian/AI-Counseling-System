import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import RiskPage from '../pages/RiskPage'

vi.mock('../api', () => ({
  fetchRiskOverview: vi.fn(),
  fetchRiskOverdue: vi.fn(),
}))

import { fetchRiskOverview, fetchRiskOverdue } from '../api'

describe('RiskPage 风险全景', () => {
  beforeEach(() => {
    vi.mocked(fetchRiskOverview).mockReset()
    vi.mocked(fetchRiskOverdue).mockReset()
  })

  it('渲染红橙黄绿分布 + 今日新增/未处置 + 逾期清单', async () => {
    vi.mocked(fetchRiskOverview).mockResolvedValue({
      levelDistribution: { red: 1, orange: 2, yellow: 3, green: 10 },
      todayNew: 5,
      unhandled: 3,
      trend7d: {},
    })
    vi.mocked(fetchRiskOverdue).mockResolvedValue([
      { riskEventId: 'evt-1', riskType: 'CRISIS', riskLevel: 3, status: 'open', detectedAt: '2026-08-09T10:00:00Z' },
    ])

    render(<RiskPage />)

    await waitFor(() => expect(screen.getAllByText('5').length).toBeGreaterThan(0))
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)
    expect(screen.getByText('CRISIS')).toBeInTheDocument()
  })

  it('接口失败 → 展示错误信息（不崩溃）', async () => {
    vi.mocked(fetchRiskOverview).mockRejectedValue(new Error('网络异常'))
    vi.mocked(fetchRiskOverdue).mockRejectedValue(new Error('网络异常'))

    render(<RiskPage />)

    await waitFor(() => expect(screen.getByText('网络异常')).toBeInTheDocument())
  })
})
