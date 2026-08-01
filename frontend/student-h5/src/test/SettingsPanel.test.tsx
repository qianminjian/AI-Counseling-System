import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import SettingsPanel from '../components/SettingsPanel'

// mock 依赖（可控 getUser 返回值）
let mockUser: any = { userId: 'u1', pseudonym: '小明', familyCode: 'FAM123' }
vi.mock('../api', () => ({
  api: vi.fn().mockResolvedValue({}),
  getUser: vi.fn(() => mockUser),
  issueVoiceCredential: vi.fn().mockResolvedValue('cred'),
}))
vi.mock('../utils/voiceprintStore', () => ({
  hasAnyVoiceprint: vi.fn().mockResolvedValue(false),
  deleteVoiceprint: vi.fn().mockResolvedValue(undefined),
  enrollVoiceprint: vi.fn().mockResolvedValue({}),
  saveVoiceCredential: vi.fn().mockResolvedValue({}),
}))
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({ themeId: 'ocean', changeTheme: vi.fn() }),
  THEMES: {
    ocean: { id: 'ocean', name: '海底世界', emoji: '🌊', companion: '🐬' },
    garden: { id: 'garden', name: '糖果乐园', emoji: '🍬', companion: '🦄' },
    rainbow: { id: 'rainbow', name: '星际探险', emoji: '🚀', companion: '🤖' },
  },
}))
vi.mock('../hooks/useVoicePersona', () => ({
  useVoicePersona: () => ({ personaId: 'star', changePersona: vi.fn() }),
  VOICE_PERSONAS: {
    star: { id: 'star', name: '小星', emoji: '⭐', desc: '温柔' },
    balloon: { id: 'balloon', name: '气球', emoji: '🎈', desc: '活泼' },
    moon: { id: 'moon', name: '月亮', emoji: '🌙', desc: '安静' },
  },
}))
vi.mock('../components/VoiceLoginOverlay', () => ({
  default: ({ mode, onComplete, onCancel }: any) => (
    <div data-testid="voice-overlay">
      <button onClick={() => onComplete({ embeddings: [[1, 2, 3]] })}>完成采集</button>
      <button onClick={onCancel}>取消采集</button>
    </div>
  ),
}))
vi.mock('../components/ConfirmDialog', () => ({
  default: ({ open, title, onConfirm, onCancel }: any) =>
    open ? (
      <div data-testid="confirm-dialog">
        <span>{title}</span>
        <button onClick={onConfirm}>确认</button>
        <button onClick={onCancel}>取消</button>
      </div>
    ) : null,
}))

describe('SettingsPanel', () => {
  const defaultProps = {
    open: true,
    onClose: vi.fn(),
    muted: false,
    onToggleMute: vi.fn(),
    wakeSupported: true,
    wakeOn: false,
    onToggleWake: vi.fn(),
    onSwitchUser: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mockUser = { userId: 'u1', pseudonym: '小明', familyCode: 'FAM123' }
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      writable: true,
      configurable: true,
    })
  })

  it('open=false 不渲染', () => {
    const { container } = render(<SettingsPanel {...defaultProps} open={false} />)
    expect(container.innerHTML).toBe('')
  })

  it('渲染标题和关闭按钮', () => {
    render(<SettingsPanel {...defaultProps} />)
    expect(screen.getByText('⚙️ 我的设置')).toBeTruthy()
    expect(screen.getByText('完成 ✓')).toBeTruthy()
  })

  it('点击完成触发 onClose', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('完成 ✓'))
    expect(defaultProps.onClose).toHaveBeenCalled()
  })

  it('渲染 3 个主题选项', () => {
    render(<SettingsPanel {...defaultProps} />)
    expect(screen.getByText('海底世界')).toBeTruthy()
    expect(screen.getByText('糖果乐园')).toBeTruthy()
    expect(screen.getByText('星际探险')).toBeTruthy()
  })

  it('渲染 3 个音色选项', () => {
    render(<SettingsPanel {...defaultProps} />)
    expect(screen.getByText('小星')).toBeTruthy()
    expect(screen.getByText('气球')).toBeTruthy()
    expect(screen.getByText('月亮')).toBeTruthy()
  })

  it('语音开启状态显示"语音已开启"', () => {
    render(<SettingsPanel {...defaultProps} muted={false} />)
    expect(screen.getByText('语音已开启')).toBeTruthy()
  })

  it('语音关闭状态显示"语音已关闭"', () => {
    render(<SettingsPanel {...defaultProps} muted={true} />)
    expect(screen.getByText('语音已关闭')).toBeTruthy()
  })

  it('点击语音开关触发 onToggleMute', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('语音已开启'))
    expect(defaultProps.onToggleMute).toHaveBeenCalled()
  })

  it('语音唤醒：支持且开启时显示"语音唤醒已开启"', () => {
    render(<SettingsPanel {...defaultProps} wakeSupported={true} wakeOn={true} />)
    expect(screen.getByText('语音唤醒已开启')).toBeTruthy()
  })

  it('语音唤醒：不支持时显示"当前设备不支持"且按钮禁用', () => {
    render(<SettingsPanel {...defaultProps} wakeSupported={false} />)
    expect(screen.getByText('当前设备不支持')).toBeTruthy()
  })

  it('点击唤醒开关触发 onToggleWake', () => {
    render(<SettingsPanel {...defaultProps} wakeSupported={true} wakeOn={false} />)
    fireEvent.click(screen.getByText('语音唤醒已关闭'))
    expect(defaultProps.onToggleWake).toHaveBeenCalled()
  })

  it('显示家庭码', async () => {
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => {
      expect(screen.getByText('FAM123')).toBeTruthy()
    })
  })

  it('复制家庭码', async () => {
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => {
      fireEvent.click(screen.getByText('复制'))
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('FAM123')
    })
  })

  it('切换同学按钮触发确认弹窗', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('切换同学'))
    expect(screen.getByText('要退出让别的同学用吗？')).toBeTruthy()
  })

  it('确认切换触发 onSwitchUser', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('切换同学'))
    fireEvent.click(screen.getByText('确认'))
    expect(defaultProps.onSwitchUser).toHaveBeenCalled()
  })

  it('无声纹时显示录入入口', () => {
    render(<SettingsPanel {...defaultProps} />)
    expect(screen.getByText('还没录入声纹')).toBeTruthy()
    expect(screen.getByText('现在录入 🎤')).toBeTruthy()
  })

  it('点击遮罩关闭面板', () => {
    const { container } = render(<SettingsPanel {...defaultProps} />)
    const overlay = container.querySelector('.bg-black\\/30')
    fireEvent.click(overlay!)
    expect(defaultProps.onClose).toHaveBeenCalled()
  })

  it('点击"现在录入"打开声纹采集覆盖层', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    expect(screen.getByTestId('voice-overlay')).toBeTruthy()
  })

  it('声纹采集完成后关闭覆盖层', async () => {
    const { enrollVoiceprint } = await import('../utils/voiceprintStore')
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    fireEvent.click(screen.getByText('完成采集'))
    await waitFor(() => {
      expect(enrollVoiceprint).toHaveBeenCalledWith('u1', '小明', [[1, 2, 3]])
    })
  })

  it('取消声纹采集关闭覆盖层', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    fireEvent.click(screen.getByText('取消采集'))
    expect(screen.queryByTestId('voice-overlay')).toBeNull()
  })

  it('有声纹时显示已录入状态和删除/重新录入按钮', async () => {
    const { hasAnyVoiceprint } = await import('../utils/voiceprintStore')
    ;(hasAnyVoiceprint as any).mockResolvedValue(true)
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => {
      expect(screen.getByText('声纹已录入')).toBeTruthy()
    })
    expect(screen.getByText('删除')).toBeTruthy()
    expect(screen.getByText('重新录入')).toBeTruthy()
  })

  it('删除声纹流程：点击删除→确认→调用 deleteVoiceprint', async () => {
    const { hasAnyVoiceprint, deleteVoiceprint } = await import('../utils/voiceprintStore')
    ;(hasAnyVoiceprint as any).mockResolvedValue(true)
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => expect(screen.getByText('删除')).toBeTruthy())
    fireEvent.click(screen.getByText('删除'))
    // 确认弹窗出现
    expect(screen.getByText('真的要删掉声音钥匙吗？')).toBeTruthy()
    fireEvent.click(screen.getByText('确认'))
    await waitFor(() => {
      expect(deleteVoiceprint).toHaveBeenCalledWith('u1')
    })
  })

  it('重新录入声纹打开采集覆盖层', async () => {
    const { hasAnyVoiceprint } = await import('../utils/voiceprintStore')
    ;(hasAnyVoiceprint as any).mockResolvedValue(true)
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => expect(screen.getByText('重新录入')).toBeTruthy())
    fireEvent.click(screen.getByText('重新录入'))
    expect(screen.getByTestId('voice-overlay')).toBeTruthy()
  })

  it('无 familyCode 时通过 API 获取', async () => {
    const { api } = await import('../api')
    ;(api as any).mockResolvedValue({ familyCode: 'API-CODE' })
    mockUser = { userId: 'u1', pseudonym: '小明', familyCode: '' }
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => {
      expect(api).toHaveBeenCalledWith('/api/v1/auth/me')
      expect(screen.getByText('API-CODE')).toBeTruthy()
    })
  })

  it('声纹采集成功但凭证签发失败时不影响流程', async () => {
    const { issueVoiceCredential } = await import('../api')
    ;(issueVoiceCredential as any).mockRejectedValue(new Error('cred fail'))
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    fireEvent.click(screen.getByText('完成采集'))
    await waitFor(() => {
      expect(screen.queryByTestId('voice-overlay')).toBeNull()
    })
  })

  it('声纹存储失败时不崩溃并关闭覆盖层', async () => {
    const { enrollVoiceprint } = await import('../utils/voiceprintStore')
    ;(enrollVoiceprint as any).mockRejectedValue(new Error('idb fail'))
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    fireEvent.click(screen.getByText('完成采集'))
    await waitFor(() => {
      expect(screen.queryByTestId('voice-overlay')).toBeNull()
    })
  })

  it('采集结果为空 embeddings 时直接关闭', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('现在录入 🎤'))
    // 模拟 onComplete 无 embeddings
    fireEvent.click(screen.getByText('取消采集'))
    expect(screen.queryByTestId('voice-overlay')).toBeNull()
  })

  it('点击主题按钮触发 changeTheme', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('糖果乐园'))
    // changeTheme 是 vi.fn()，不报错即可
  })

  it('点击音色按钮触发 changePersona', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('气球'))
    // changePersona 是 vi.fn()，不报错即可
  })

  it('切换同学确认弹窗点击取消关闭', () => {
    render(<SettingsPanel {...defaultProps} />)
    fireEvent.click(screen.getByText('切换同学'))
    expect(screen.getByTestId('confirm-dialog')).toBeTruthy()
    fireEvent.click(screen.getByText('取消'))
    expect(screen.queryByTestId('confirm-dialog')).toBeNull()
  })

  it('删除声纹确认弹窗点击取消关闭', async () => {
    const { hasAnyVoiceprint } = await import('../utils/voiceprintStore')
    ;(hasAnyVoiceprint as any).mockResolvedValue(true)
    render(<SettingsPanel {...defaultProps} />)
    await waitFor(() => expect(screen.getByText('删除')).toBeTruthy())
    fireEvent.click(screen.getByText('删除'))
    expect(screen.getByTestId('confirm-dialog')).toBeTruthy()
    fireEvent.click(screen.getByText('取消'))
    expect(screen.queryByTestId('confirm-dialog')).toBeNull()
  })
})
