import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import ParentReport from '../components/ParentReport'

describe('ParentReport', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('缺少 token 显示错误', async () => {
    // 模拟无 token URL
    Object.defineProperty(window, 'location', {
      value: { search: '' },
      writable: true,
      configurable: true,
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('缺少访问凭证')).toBeTruthy()
      expect(screen.getByText('请联系老师重新分享链接')).toBeTruthy()
    })
  })

  it('加载中显示 loading', () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=abc' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockReturnValue(new Promise(() => {})) // 永不 resolve

    render(<ParentReport />)
    expect(screen.getByText('加载中...')).toBeTruthy()
  })

  it('成功加载报告数据', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=valid-token' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockResolvedValue({
      json: () => Promise.resolve({
        success: true,
        data: {
          studentNickname: '小明',
          gradeCode: '三',
          classCode: '2班',
          sessionCount: 5,
          totalTurns: 42,
          maxRiskLevel: 0,
          riskLabel: '状态良好',
          emotionDistribution: { happy: 8, calm: 3, sad: 1 },
          generatedAt: '2026-07-28T10:00:00Z',
        },
      }),
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('🌈 情绪周报')).toBeTruthy()
      expect(screen.getByText(/小明/)).toBeTruthy()
      expect(screen.getByText('5')).toBeTruthy()
      expect(screen.getByText('42')).toBeTruthy()
      expect(screen.getByText('状态良好')).toBeTruthy()
    })
  })

  it('情绪分布渲染', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=t' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockResolvedValue({
      json: () => Promise.resolve({
        success: true,
        data: {
          studentNickname: '花花',
          gradeCode: '四',
          classCode: '1班',
          sessionCount: 2,
          totalTurns: 10,
          maxRiskLevel: 1,
          riskLabel: '需关注',
          emotionDistribution: { happy: 5, sad: 2 },
          generatedAt: '2026-07-28T10:00:00Z',
        },
      }),
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('情绪分布')).toBeTruthy()
      expect(screen.getByText('😊 开心')).toBeTruthy()
      expect(screen.getByText('😢 难过')).toBeTruthy()
    })
  })

  it('API 返回 success=false 显示错误', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=bad' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockResolvedValue({
      json: () => Promise.resolve({ success: false, message: '凭证已过期' }),
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('凭证已过期')).toBeTruthy()
    })
  })

  it('网络错误显示提示', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=x' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockRejectedValue(new Error('network'))

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('网络错误')).toBeTruthy()
    })
  })

  it('无情绪记录时显示空状态', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=t' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockResolvedValue({
      json: () => Promise.resolve({
        success: true,
        data: {
          studentNickname: '空空',
          gradeCode: '一',
          classCode: '3班',
          sessionCount: 0,
          totalTurns: 0,
          maxRiskLevel: 0,
          riskLabel: '无数据',
          emotionDistribution: {},
          generatedAt: '2026-07-28T10:00:00Z',
        },
      }),
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('本周暂无情绪记录')).toBeTruthy()
    })
  })

  it('显示温馨提示', async () => {
    Object.defineProperty(window, 'location', {
      value: { search: '?token=t' },
      writable: true,
      configurable: true,
    })
    ;(fetch as any).mockResolvedValue({
      json: () => Promise.resolve({
        success: true,
        data: {
          studentNickname: 'A',
          gradeCode: '二',
          classCode: '1班',
          sessionCount: 1,
          totalTurns: 5,
          maxRiskLevel: 0,
          riskLabel: '良好',
          emotionDistribution: { happy: 1 },
          generatedAt: '2026-07-28T10:00:00Z',
        },
      }),
    })

    render(<ParentReport />)
    await waitFor(() => {
      expect(screen.getByText('温馨提示')).toBeTruthy()
      expect(screen.getByText(/您的理解和陪伴是最好的支持/)).toBeTruthy()
    })
  })
})
