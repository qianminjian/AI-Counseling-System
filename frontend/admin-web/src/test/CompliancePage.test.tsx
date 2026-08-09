import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import CompliancePage from '../pages/CompliancePage'

vi.mock('../api', () => ({
  fetchConsentStats: vi.fn(),
}))

import { fetchConsentStats } from '../api'

describe('CompliancePage 数据合规中心', () => {
  beforeEach(() => {
    vi.mocked(fetchConsentStats).mockReset()
  })

  it('渲染告知同意覆盖统计', async () => {
    vi.mocked(fetchConsentStats).mockResolvedValue({
      total: 200,
      last7d: 15,
      byType: { VOICEPRINT: 120, PRIVACY: 80 },
    })

    render(<CompliancePage />)

    await waitFor(() => expect(screen.getByText('200')).toBeInTheDocument())
    expect(screen.getByText('15')).toBeInTheDocument()
    expect(screen.getByText('VOICEPRINT')).toBeInTheDocument()
  })
})
