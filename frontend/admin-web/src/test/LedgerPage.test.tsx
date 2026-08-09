import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import LedgerPage from '../pages/LedgerPage'

vi.mock('../api', () => ({
  fetchDeadLedger: vi.fn(),
}))

import { fetchDeadLedger } from '../api'

describe('LedgerPage 处置台账', () => {
  beforeEach(() => {
    vi.mocked(fetchDeadLedger).mockReset()
  })

  it('渲染 dead 台账（脱敏字段）', async () => {
    vi.mocked(fetchDeadLedger).mockResolvedValue([
      { riskEventId: 'evt-1', tenantId: 't1', riskType: 'CRISIS', riskLevel: 3, status: 'open', detectedAt: '2026-08-09T10:00:00Z', notifyStatus: 'dead' },
    ])

    render(<LedgerPage />)

    await waitFor(() => expect(screen.getByText('CRISIS')).toBeInTheDocument())
    expect(screen.getByText(/脱敏视图/)).toBeInTheDocument()
  })
})
