import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import ReportPage from '../pages/report/index'

// Mock API
vi.mock('../api/index', () => ({
  getReport: vi.fn()
}))

// Mock auth utils
vi.mock('../utils/auth', () => ({
  getUser: vi.fn(),
  clearAuth: vi.fn()
}))

import { getReport } from '../api/index'
import { getUser, clearAuth } from '../utils/auth'

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
    vi.mocked(getReport).mockResolvedValue({ data: mockReport })
    vi.mocked(clearAuth).mockImplementation(() => {})
  })

  it('渲染页面标题和家长问候', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    expect(screen.getByText('情绪周报')).toBeInTheDocument()
    expect(screen.getByText('测试家长，您好')).toBeInTheDocument()
  })

  it('默认加载第一个孩子的周报', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(getReport).toHaveBeenCalledWith('c1')
    })
  })

  it('显示周报概览数据', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument()
      expect(screen.getByText('42')).toBeInTheDocument()
      expect(screen.getByText('良好')).toBeInTheDocument()
    })
  })

  it('显示情绪分布', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(screen.getByText('开心')).toBeInTheDocument()
      expect(screen.getByText('平静')).toBeInTheDocument()
    })
  })

  it('多孩子时显示切换标签', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    expect(screen.getByText('小明')).toBeInTheDocument()
    expect(screen.getByText('小红')).toBeInTheDocument()
  })

  it('切换孩子后重新加载周报', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
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
      data: { ...mockReport, sessionCount: 0, totalTurns: 0 }
    })
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(screen.getByText(/暂无对话记录/)).toBeInTheDocument()
    })
  })

  it('API 失败时显示错误信息', async () => {
    vi.mocked(getReport).mockRejectedValue(new Error('网络错误'))
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(screen.getByText('网络错误')).toBeInTheDocument()
    })
  })

  it('渲染退出按钮', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    expect(screen.getByText('退出')).toBeInTheDocument()
  })

  it('渲染数据授权管理入口', async () => {
    render(
      <MemoryRouter>
        <ReportPage />
      </MemoryRouter>
    )
    await waitFor(() => {
      expect(screen.getByText('数据授权管理')).toBeInTheDocument()
    })
  })
})
