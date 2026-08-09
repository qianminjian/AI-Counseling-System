import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import PromptPage from '../pages/PromptPage'

vi.mock('../api', () => ({
  fetchPromptVersions: vi.fn(),
  getAdminName: vi.fn(() => 'admin'),
  promptAction: vi.fn(),
}))

import { fetchPromptVersions, promptAction } from '../api'

describe('PromptPage 审核发布流', () => {
  beforeEach(() => {
    vi.mocked(fetchPromptVersions).mockReset()
    vi.mocked(promptAction).mockReset()
  })

  it('按状态渲染操作按钮（draft→提交审核 / pending_review→审核通过 / approved→激活）', async () => {
    vi.mocked(fetchPromptVersions).mockResolvedValue([
      { versionId: 'v1', templateKey: 'chat_default', version: 3, abGroup: 'A', isActive: false, status: 'draft', contentLength: 100 },
      { versionId: 'v2', templateKey: 'chat_default', version: 2, abGroup: 'B', isActive: false, status: 'pending_review', contentLength: 200 },
      { versionId: 'v3', templateKey: 'chat_default', version: 1, abGroup: 'A', isActive: true, status: 'active', contentLength: 300 },
    ])

    render(<PromptPage />)

    await waitFor(() => expect(screen.getByText('提交审核')).toBeInTheDocument())
    expect(screen.getByText('审核通过')).toBeInTheDocument()
    // active 版本无操作按钮
    expect(screen.queryByRole('button', { name: '激活' })).not.toBeInTheDocument()
  })

  it('审核通过：promptAction 携带 reviewer（登录账号留痕）', async () => {
    vi.mocked(fetchPromptVersions).mockResolvedValue([
      { versionId: 'v2', templateKey: 'chat_default', version: 2, abGroup: 'B', isActive: false, status: 'pending_review', contentLength: 200 },
    ])
    vi.mocked(promptAction).mockResolvedValue(undefined)

    render(<PromptPage />)

    await waitFor(() => expect(screen.getByText('审核通过')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '审核通过' }))

    await waitFor(() => {
      expect(promptAction).toHaveBeenCalledWith('/versions/v2/review', { reviewer: 'admin' })
    })
  })
})
