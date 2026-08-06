import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import ConsentPage from '../pages/consent/index'

// Mock API
vi.mock('../api/index', () => ({
  withdrawConsent: vi.fn()
}))

// Mock auth utils
vi.mock('../utils/auth', () => ({
  getUser: vi.fn()
}))

import { withdrawConsent } from '../api/index'
import { getUser } from '../utils/auth'

const mockUser = {
  parentId: 'p1',
  displayName: '测试家长',
  children: [
    { userId: 'c1', nickname: '小明', gradeCode: '三年级', classCode: '1班' }
  ]
}

describe('ConsentPage', () => {
  beforeEach(() => {
    vi.mocked(getUser).mockReturnValue(mockUser)
    vi.mocked(withdrawConsent).mockResolvedValue({ data: { message: '已成功撤回授权' } })
  })

  it('渲染页面标题', () => {
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    expect(screen.getByText('数据授权管理')).toBeInTheDocument()
  })

  it('渲染授权说明', () => {
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    expect(screen.getByText('授权说明')).toBeInTheDocument()
    expect(screen.getByText(/您已授权/)).toBeInTheDocument()
  })

  it('渲染孩子选择按钮', () => {
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    expect(screen.getByText(/小明/)).toBeInTheDocument()
  })

  it('选择孩子后显示撤回按钮', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    await user.click(screen.getByText(/小明/))
    expect(screen.getByText(/撤回「小明」的授权/)).toBeInTheDocument()
  })

  it('点击撤回进入二次确认', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    expect(screen.getByText(/确认撤回？/)).toBeInTheDocument()
    expect(screen.getByText('确认撤回')).toBeInTheDocument()
    expect(screen.getByText('取消')).toBeInTheDocument()
  })

  it('确认撤回后调用 API 并显示结果', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))

    await waitFor(() => {
      expect(withdrawConsent).toHaveBeenCalledWith('c1')
    })
    await waitFor(() => {
      expect(screen.getByText(/已成功撤回授权/)).toBeInTheDocument()
    })
  })

  it('撤回失败时显示错误信息', async () => {
    vi.mocked(withdrawConsent).mockRejectedValue(new Error('撤回失败，请重试'))
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))

    await waitFor(() => {
      expect(screen.getByText('撤回失败，请重试')).toBeInTheDocument()
    })
  })

  it('取消二次确认返回选择界面', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ConsentPage />
      </MemoryRouter>
    )
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('取消'))
    // 取消后确认区域消失，撤回按钮重新显示
    expect(screen.getByText(/撤回「小明」的授权/)).toBeInTheDocument()
  })
})
