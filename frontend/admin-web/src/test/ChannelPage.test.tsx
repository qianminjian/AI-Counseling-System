import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import ChannelPage from '../pages/ChannelPage'

vi.mock('../api', () => ({
  fetchChannelStats: vi.fn(),
}))

import { fetchChannelStats } from '../api'

describe('ChannelPage 通知渠道', () => {
  beforeEach(() => {
    vi.mocked(fetchChannelStats).mockReset()
  })

  it('渲染渠道发送统计', async () => {
    vi.mocked(fetchChannelStats).mockResolvedValue({
      total: 300,
      byChannel: { wecom: 200, sms: 80, inapp: 20 },
    })

    render(<ChannelPage />)

    await waitFor(() => expect(screen.getByText('300')).toBeInTheDocument())
    expect(screen.getByText('200')).toBeInTheDocument()
  })
})
