import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import ConfigPage from '../pages/ConfigPage'

vi.mock('../api', () => ({
  fetchConfigs: vi.fn(),
  updateConfig: vi.fn(),
}))

import { fetchConfigs, updateConfig } from '../api'

describe('ConfigPage 配置注册表', () => {
  beforeEach(() => {
    vi.mocked(fetchConfigs).mockReset()
    vi.mocked(updateConfig).mockReset()
  })

  it('渲染配置列表（HOT/只读操作列 + SECRET 掩码标签）', async () => {
    vi.mocked(fetchConfigs).mockResolvedValue([
      { configKey: 'mindsafe.safety.voiceprint-threshold', domain: 'security', value: '0.70', valueType: 'number', sensitive: 'NORMAL', effectMode: 'HOT', description: '声纹阈值' },
      { configKey: 'mindsafe.alert.wecom.webhook-url', domain: 'alert', value: '', valueType: 'string', sensitive: 'SECRET', effectMode: 'HOT', description: '企微 webhook' },
      { configKey: 'mindsafe.slow.restart-key', domain: 'system', value: 'x', valueType: 'string', sensitive: 'NORMAL', effectMode: 'RESTART', description: '只读键' },
    ])

    render(<ConfigPage />)

    await waitFor(() => {
      expect(screen.getByText('mindsafe.safety.voiceprint-threshold')).toBeInTheDocument()
    })
    expect(screen.getByText('0.70')).toBeInTheDocument()
    // SECRET 掩码
    expect(screen.getByText('***已配置***')).toBeInTheDocument()
    // RESTART 只读
    expect(screen.getAllByText('只读').length).toBeGreaterThan(0)
    // HOT 修改按钮（2 个 HOT 键）
    expect(screen.getAllByRole('button', { name: /修\s*改/ }).length).toBe(2)
  })

  it('HOT 修改：弹窗提交调用 updateConfig（configKey/reason 透传）', async () => {
    vi.mocked(fetchConfigs).mockResolvedValue([
      { configKey: 'mindsafe.tts.synthesize-timeout', domain: 'voice', value: '30', valueType: 'number', sensitive: 'NORMAL', effectMode: 'HOT', description: 'TTS 超时' },
    ])
    vi.mocked(updateConfig).mockResolvedValue(undefined)

    render(<ConfigPage />)

    await waitFor(() => expect(screen.getByText('mindsafe.tts.synthesize-timeout')).toBeInTheDocument())
    fireEvent.click(screen.getAllByRole('button', { name: /修\s*改/ })[0])
    // Modal 打开：输入新值与原因
    await waitFor(() => expect(screen.getByText('确认修改')).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText('新值'), { target: { value: '45' } })
    fireEvent.change(screen.getByLabelText('变更原因'), { target: { value: '调高超时' } })
    fireEvent.click(screen.getByRole('button', { name: '确认修改' }))

    await waitFor(() => {
      expect(updateConfig).toHaveBeenCalledWith('mindsafe.tts.synthesize-timeout', '45', '调高超时')
    })
  })
})
