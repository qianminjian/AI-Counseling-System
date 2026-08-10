import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import MetricsPage from '../pages/MetricsPage'

vi.mock('../api', () => ({
  fetchMetricsQuery: vi.fn(),
}))

import { fetchMetricsQuery } from '../api'

describe('MetricsPage 指标看板（ADMIN-P1-07/09）', () => {
  beforeEach(() => {
    vi.mocked(fetchMetricsQuery).mockReset()
  })

  it('渲染全部指标卡 + 白名单表达式逐项查询', async () => {
    vi.mocked(fetchMetricsQuery).mockResolvedValue({
      status: 'success',
      data: { result: [{ metric: { __name__: 'tts_synthesize_requests_total' }, value: [1700000000, '42'] }] },
    })

    render(<MetricsPage />)

    await waitFor(() => {
      expect(screen.getByText('TTS 合成请求量')).toBeInTheDocument()
    })
    // 数值展示（42 条请求，全部指标卡均 mock 返回 42）
    expect(screen.getAllByText('42').length).toBeGreaterThan(0)
    // 白名单表达式全部被调用（10 个指标卡）
    expect(fetchMetricsQuery).toHaveBeenCalledTimes(10)
  })

  it('查询失败 → 该指标显示占位符（不崩溃）', async () => {
    vi.mocked(fetchMetricsQuery).mockRejectedValue(new Error('网络错误'))

    render(<MetricsPage />)

    await waitFor(() => {
      expect(screen.getByText('TTS 合成请求量')).toBeInTheDocument()
    })
    // 占位符 —（无样本）
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})
