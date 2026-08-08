// doing/73 T3：ReportPage Taro 化测试（services mock + Taro 导航 mock + userEvent 点击）
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import ReportPage from '../pages/report/index'

// Mock Taro 导航
vi.mock('@tarojs/taro', () => ({
  default: { redirectTo: vi.fn(), navigateTo: vi.fn() }
}))

// Mock 服务层
vi.mock('../services/index', () => ({
  getReport: vi.fn()
}))

// Mock auth utils（isAuthenticated 返回 true：守卫不跳转，聚焦页面行为）
vi.mock('../utils/auth', () => ({
  getUser: vi.fn(),
  clearAuth: vi.fn(),
  isAuthenticated: vi.fn(() => true)
}))

import Taro from '@tarojs/taro'
import { getReport } from '../services/index'
import { getUser, clearAuth, isAuthenticated } from '../utils/auth'

const mockedTaro = vi.mocked(Taro)

const mockUser = {
  parentId: 'p1',
  displayName: '测试家长',
  children: [
    { userId: 'c1', nickname: '小明', gradeCode: '三年级', classCode: '1班' },
    { userId: 'c2', nickname: '小红', gradeCode: '五年级', classCode: '2班' }
  ]
}

const mockReport = {
  sessionCount: 5,
  totalTurns: 42,
  maxRiskLevel: 1,
  riskLabel: '良好',
  emotionDistribution: { '开心': 3, '平静': 2 }
}

describe('ReportPage', () => {
  beforeEach(() => {
    vi.mocked(getUser).mockReturnValue(mockUser)
    vi.mocked(getReport).mockResolvedValue(mockReport)
    vi.mocked(clearAuth).mockImplementation(() => {})
    vi.mocked(isAuthenticated).mockImplementation(() => true)
    mockedTaro.redirectTo.mockClear()
    mockedTaro.navigateTo.mockClear()
  })

  it('渲染页面标题和家长问候', async () => {
    render(<ReportPage />)
    expect(screen.getByText('情绪周报')).toBeInTheDocument()
    expect(screen.getByText('测试家长，您好')).toBeInTheDocument()
  })

  it('默认加载第一个孩子的周报', async () => {
    render(<ReportPage />)
    await waitFor(() => {
      expect(getReport).toHaveBeenCalledWith('c1')
    })
  })

  it('显示周报概览数据', async () => {
    render(<ReportPage />)
    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument()
      expect(screen.getByText('42')).toBeInTheDocument()
      expect(screen.getByText('良好')).toBeInTheDocument()
    })
  })

  it('显示情绪分布', async () => {
    render(<ReportPage />)
    await waitFor(() => {
      expect(screen.getByText('开心')).toBeInTheDocument()
      expect(screen.getByText('平静')).toBeInTheDocument()
    })
  })

  it('多孩子时显示切换标签', async () => {
    render(<ReportPage />)
    expect(screen.getByText('小明')).toBeInTheDocument()
    expect(screen.getByText('小红')).toBeInTheDocument()
  })

  it('切换孩子后重新加载周报', async () => {
    const user = userEvent.setup()
    render(<ReportPage />)
    await waitFor(() => {
      expect(getReport).toHaveBeenCalledWith('c1')
    })
    await user.click(screen.getByText('小红'))
    await waitFor(() => {
      expect(getReport).toHaveBeenCalledWith('c2')
    })
  })

  it('0 次对话时显示空状态提示', async () => {
    vi.mocked(getReport).mockResolvedValue({
      ...mockReport, sessionCount: 0, totalTurns: 0
    })
    render(<ReportPage />)
    await waitFor(() => {
      expect(screen.getByText(/暂无对话记录/)).toBeInTheDocument()
    })
  })

  it('API 失败时显示错误信息', async () => {
    vi.mocked(getReport).mockRejectedValue(new Error('网络错误'))
    render(<ReportPage />)
    await waitFor(() => {
      expect(screen.getByText('网络错误')).toBeInTheDocument()
    })
  })

  it('渲染退出按钮，点击后清认证并跳回登录页', async () => {
    const user = userEvent.setup()
    render(<ReportPage />)
    await user.click(screen.getByText('退出'))
    expect(clearAuth).toHaveBeenCalled()
    expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/' })
  })

  it('渲染数据授权管理入口，点击跳转授权页', async () => {
    const user = userEvent.setup()
    render(<ReportPage />)
    await waitFor(() => {
      expect(screen.getByText('数据授权管理')).toBeInTheDocument()
    })
    await user.click(screen.getByText('数据授权管理'))
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/consent' })
  })

  it('未登录时守卫跳转登录页', () => {
    vi.mocked(getUser).mockReturnValue(null)
    vi.mocked(isAuthenticated).mockImplementation(() => false)
    render(<ReportPage />)
    expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/' })
  })
})
