import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import AlertPage from '../pages/AlertPage'

vi.mock('../api', () => ({
  fetchAlertEvents: vi.fn(),
  ackAlertEvent: vi.fn(),
  getAdminRole: vi.fn(() => 'ops_admin'),
}))

import { fetchAlertEvents, ackAlertEvent } from '../api'

const firingAlert = {
  eventId: 'evt-1',
  source: 'alertmanager',
  ruleName: 'TtsPrimaryEngineDegraded',
  severity: 'WARNING',
  status: 'firing',
  summary: 'TTS 主引擎持续降级',
  notifyStatus: 'SUCCESS',
  firedAt: '2026-08-10T10:00:00Z',
}

describe('AlertPage 告警中心（ADMIN-P1-08/09）', () => {
  beforeEach(() => {
    vi.mocked(fetchAlertEvents).mockReset()
    vi.mocked(ackAlertEvent).mockReset()
    vi.mocked(fetchAlertEvents).mockResolvedValue([firingAlert])
  })

  it('渲染告警台账（规则/级别/状态/推送状态）', async () => {
    render(<AlertPage />)

    await waitFor(() => {
      expect(screen.getByText('TtsPrimaryEngineDegraded')).toBeInTheDocument()
    })
    expect(screen.getByText('WARNING')).toBeInTheDocument()
    expect(screen.getByText('firing')).toBeInTheDocument()
    expect(screen.getByText('SUCCESS')).toBeInTheDocument()
  })

  it('ops_admin：firing 告警可确认（弹窗 → reason 必填 → ack 调用 + 刷新）', async () => {
    vi.mocked(ackAlertEvent).mockResolvedValue(undefined)
    render(<AlertPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /确\s*认/ })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /确\s*认/ }))

    // reason 必填：空提交被拦截
    const okBtn = screen.getAllByRole('button', { name: /确\s*认/ }).at(-1)!
    expect(okBtn).toBeDisabled()

    fireEvent.change(screen.getByPlaceholderText('确认原因（必填，审计留痕）'), { target: { value: '已人工处置' } })
    fireEvent.click(okBtn)

    await waitFor(() => {
      expect(ackAlertEvent).toHaveBeenCalledWith('evt-1', '已人工处置')
    })
  })
})
