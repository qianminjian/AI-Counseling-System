import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import DegradationPage from '../pages/DegradationPage'

vi.mock('../api', () => ({
  fetchDegradationMatrix: vi.fn(),
  fetchDegradationEvents: vi.fn(),
  degradationOverride: vi.fn(),
  cancelDegradationOverride: vi.fn(),
}))

import { fetchDegradationMatrix, fetchDegradationEvents, degradationOverride, cancelDegradationOverride } from '../api'

describe('DegradationPage 降级矩阵', () => {
  beforeEach(() => {
    vi.mocked(fetchDegradationMatrix).mockReset()
    vi.mocked(fetchDegradationEvents).mockReset()
    vi.mocked(degradationOverride).mockReset()
    vi.mocked(cancelDegradationOverride).mockReset()
  })

  it('渲染矩阵（覆盖态标签）+ 事件时间线', async () => {
    vi.mocked(fetchDegradationMatrix).mockResolvedValue([
      { point: 'tts', overridden: true, overrideTo: 'edge_tts', currentState: 'edge_tts', availableStates: ['cosyvoice', 'edge_tts'], latestEvent: { from: 'cosyvoice', to: 'edge_tts', triggerType: 'auto', occurredAt: '2026-08-09T10:00:00Z' } },
      { point: 'llm', overridden: false, currentState: 'primary', availableStates: ['primary', 'backup'] },
    ])
    vi.mocked(fetchDegradationEvents).mockResolvedValue([
      { point: 'tts', fromState: 'cosyvoice', toState: 'edge_tts', triggerType: 'auto', occurredAt: '2026-08-09T10:00:00Z' },
    ])

    render(<DegradationPage />)

    await waitFor(() => expect(screen.getAllByText('tts').length).toBeGreaterThan(0))
    expect(screen.getByText(/已覆盖 → edge_tts/)).toBeInTheDocument()
    // 矩阵最近事件列（函数匹配器对文本拆分鲁棒）
    expect(screen.getByText((content) => content.includes('cosyvoice→edge_tts'))).toBeInTheDocument()
    // 事件时间线：触发方式 Tag + 从→到列
    expect(screen.getByText('cosyvoice → edge_tts')).toBeInTheDocument()
  })

  it('手动切换：弹窗提交调用 degradationOverride（point/to/reason）', async () => {
    vi.mocked(fetchDegradationMatrix).mockResolvedValue([
      { point: 'llm', overridden: false, currentState: 'primary', availableStates: ['primary', 'backup'] },
    ])
    vi.mocked(fetchDegradationEvents).mockResolvedValue([])
    vi.mocked(degradationOverride).mockResolvedValue(undefined)

    render(<DegradationPage />)

    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: /切\s*换/ }).length).toBeGreaterThan(0))
    fireEvent.click(screen.getAllByRole('button', { name: /切\s*换/ })[0])
    await waitFor(() => expect(screen.getByRole('button', { name: /确认切换/ })).toBeInTheDocument())
    // antd Select：mouseDown 打开下拉 → 点击 option
    fireEvent.mouseDown(screen.getByLabelText('切换目标'))
    await waitFor(() => expect(screen.getByTitle('backup')).toBeInTheDocument())
    fireEvent.click(screen.getByTitle('backup'))
    fireEvent.change(screen.getByLabelText('切换原因'), { target: { value: '主引擎故障' } })
    fireEvent.click(screen.getByRole('button', { name: /确认切换/ }))

    await waitFor(() => {
      expect(degradationOverride).toHaveBeenCalledWith('llm', 'backup', '主引擎故障')
    })
  })
})
