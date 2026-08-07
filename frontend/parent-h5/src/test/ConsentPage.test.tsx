// doing/73 T3：ConsentPage Taro 化测试（services mock + Taro 导航 mock + userEvent 点击）
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import ConsentPage from '../pages/consent/index'

// Mock Taro 导航
vi.mock('@tarojs/taro', () => ({
  default: { redirectTo: vi.fn(), navigateTo: vi.fn() }
}))

// Mock 服务层
vi.mock('../services/index', () => ({
  withdrawConsent: vi.fn()
}))

// Mock auth utils（isAuthenticated 返回 true：守卫不跳转，聚焦页面行为）
vi.mock('../utils/auth', () => ({
  getUser: vi.fn(),
  isAuthenticated: vi.fn(() => true)
}))

import Taro from '@tarojs/taro'
import { withdrawConsent } from '../services/index'
import { getUser, isAuthenticated } from '../utils/auth'

const mockedTaro = vi.mocked(Taro)
const mockedIsAuth = vi.mocked(isAuthenticated)

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
    mockedTaro.redirectTo.mockClear()
    mockedTaro.navigateTo.mockClear()
  })

  it('渲染页面标题', () => {
    render(<ConsentPage />)
    expect(screen.getByText('数据授权管理')).toBeInTheDocument()
  })

  it('未登录时守卫重定向到登录页', () => {
    mockedIsAuth.mockReturnValueOnce(false)
    render(<ConsentPage />)
    expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/' })
  })

  it('渲染授权说明', () => {
    render(<ConsentPage />)
    expect(screen.getByText('授权说明')).toBeInTheDocument()
    expect(screen.getByText(/您已授权/)).toBeInTheDocument()
  })

  it('渲染孩子选择按钮', () => {
    render(<ConsentPage />)
    expect(screen.getByText(/小明/)).toBeInTheDocument()
  })

  it('选择孩子后显示撤回按钮', async () => {
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    expect(screen.getByText(/撤回「小明」的授权/)).toBeInTheDocument()
  })

  it('点击撤回进入二次确认', async () => {
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    expect(screen.getByText(/确认撤回？/)).toBeInTheDocument()
    expect(screen.getByText('确认撤回')).toBeInTheDocument()
    expect(screen.getByText('取消')).toBeInTheDocument()
  })

  it('确认撤回后调用 API 并显示结果', async () => {
    const user = userEvent.setup()
    render(<ConsentPage />)
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
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))

    await waitFor(() => {
      expect(screen.getByText('撤回失败，请重试')).toBeInTheDocument()
    })
  })

  it('取消二次确认返回选择界面', async () => {
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('取消'))
    // 取消后确认区域消失，撤回按钮重新显示
    expect(screen.getByText(/撤回「小明」的授权/)).toBeInTheDocument()
  })

  it('孩子无年级/班级时省略括号内文案', () => {
    vi.mocked(getUser).mockReturnValue({
      parentId: 'p1',
      displayName: '测试家长',
      children: [{ userId: 'c2', nickname: '小红' }]
    })
    render(<ConsentPage />)
    expect(screen.getByText('小红（）')).toBeInTheDocument()
  })

  it('撤回返回无 data 形态时兼容（message 兜底）', async () => {
    vi.mocked(withdrawConsent).mockResolvedValue({ message: '兜底成功' })
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))
    await waitFor(() => {
      expect(screen.getByText(/兜底成功/)).toBeInTheDocument()
    })
  })

  it('撤回异常为非 Error 对象时显示通用错误', async () => {
    vi.mocked(withdrawConsent).mockRejectedValue('boom')
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))
    await waitFor(() => {
      expect(screen.getByText('操作失败')).toBeInTheDocument()
    })
  })

  it('撤回成功无 message 时显示默认文案', async () => {
    vi.mocked(withdrawConsent).mockResolvedValue({ data: {} })
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/小明/))
    await user.click(screen.getByText(/撤回「小明」的授权/))
    await user.click(screen.getByText('确认撤回'))
    await waitFor(() => {
      expect(screen.getByText(/已撤回授权/)).toBeInTheDocument()
    })
  })

  it('返回按钮跳转周报页', async () => {
    const user = userEvent.setup()
    render(<ConsentPage />)
    await user.click(screen.getByText(/返回/))
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/report' })
  })
})
