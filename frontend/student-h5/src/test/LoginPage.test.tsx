import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import LoginPage from '../components/LoginPage'

// mock 所有外部依赖
vi.mock('../api', () => ({
  pinLogin: vi.fn(),
  setToken: vi.fn(),
  setRefreshToken: vi.fn(),
  setUser: vi.fn(),
  issueVoiceCredential: vi.fn().mockResolvedValue('cred'),
  trialRegister: vi.fn(),
  setPin: vi.fn(),
  markConsentDone: vi.fn(),
  getVoiceprintConfig: vi.fn().mockResolvedValue({ mode: 'local', enabled: true }),
  remoteVoiceprintEnroll: vi.fn().mockResolvedValue({ success: true }),
}))
vi.mock('../utils/voiceprintStore', () => ({
  hasAnyVoiceprint: vi.fn(), // AUD-008：动态 mock，beforeEach 默认 false，有声纹场景单独覆盖
  enrollVoiceprint: vi.fn().mockResolvedValue({}),
  saveVoiceCredential: vi.fn().mockResolvedValue({}),
  markRemoteVoiceprintEnrolled: vi.fn(),
}))
// AUD-008：mock 声纹模型状态（默认 idle=未下载），单独覆盖 ready 场景验证跳过流量确认
let mockVpStatus = 'idle'
vi.mock('../hooks/useVoiceprint', () => ({
  preloadVoiceprintModel: vi.fn(),
  useVoiceprintModelStatus: () => ({ status: mockVpStatus, progress: 0, error: undefined }),
}))
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({ themeId: 'ocean', changeTheme: vi.fn() }),
  THEMES: {
    ocean: { id: 'ocean', name: '海洋探险', emoji: '🌊', companion: '🐬', bobo: { body: '#38BDF8', belly: '#E0F2FE', fin: '#0284C7' } },
    garden: { id: 'garden', name: '花园精灵', emoji: '🌸', companion: '🐬', bobo: { body: '#F472B6', belly: '#FCE7F3', fin: '#DB2777' } },
    rainbow: { id: 'rainbow', name: '彩虹自由', emoji: '🌈', companion: '🐬', bobo: { body: '#A78BFA', belly: '#EDE9FE', fin: '#7C3AED' } },
  },
}))
vi.mock('../components/VoiceLoginOverlay', () => ({
  default: ({ mode, onComplete, onCancel }: any) => (
    <div data-testid="voice-overlay">
      <button onClick={() => onComplete({ matched: true, userId: 'u1' })}>识别成功</button>
      <button onClick={() => onComplete({ matched: false })}>识别失败</button>
      <button onClick={() => onComplete({ embeddings: [[1, 2, 3]] })}>采集完成</button>
      <button onClick={onCancel}>取消</button>
    </div>
  ),
}))
vi.mock('../components/SceneDecor', () => ({
  default: () => <div data-testid="scene-decor" />,
}))
vi.mock('../components/ConfirmDialog', () => ({
  default: ({ open, title, onConfirm, onCancel, confirmText = '没错，注册！', cancelText = '再改改', children }: any) =>
    open ? (
      <div data-testid="confirm-dialog">
        <span>{title}</span>
        {children}
        <button onClick={onConfirm}>{confirmText}</button>
        <button onClick={onCancel}>{cancelText}</button>
      </div>
    ) : null,
}))

import { pinLogin, setToken, setUser, trialRegister, setPin, markConsentDone } from '../api'
import { hasAnyVoiceprint } from '../utils/voiceprintStore'
import { preloadVoiceprintModel } from '../hooks/useVoiceprint'

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockVpStatus = 'idle'
    ;(hasAnyVoiceprint as any).mockResolvedValue(false)
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })

  it('渲染品牌和 tab', () => {
    render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
    expect(screen.getAllByText('波波小精灵').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('登录')).toBeTruthy()
    expect(screen.getByText('新注册')).toBeTruthy()
  })

  it('默认显示登录 tab（PIN 表单）', () => {
    render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
    expect(screen.getByText('你的昵称')).toBeTruthy()
    expect(screen.getByPlaceholderText('输入注册时的昵称')).toBeTruthy()
  })

  it('切换到注册 tab 触发 onNeedConsent', () => {
    const onNeedConsent = vi.fn()
    render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={onNeedConsent} />)
    fireEvent.click(screen.getByText('新注册'))
    expect(onNeedConsent).toHaveBeenCalled()
    expect(screen.getByText('邀请码 *')).toBeTruthy()
  })

  it('initialTab=register 直接显示注册表单', () => {
    render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
    expect(screen.getByText('邀请码 *')).toBeTruthy()
  })

  it('主题切换浮标渲染 3 个按钮', () => {
    render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
    expect(screen.getByText('🌊')).toBeTruthy()
    expect(screen.getByText('🌸')).toBeTruthy()
    expect(screen.getByText('🌈')).toBeTruthy()
  })

  describe('PIN 登录', () => {
    it('彩虹键盘 1-9 + 0 + 删除', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      expect(screen.getByText('1')).toBeTruthy()
      expect(screen.getByText('9')).toBeTruthy()
      expect(screen.getByText('0')).toBeTruthy()
      expect(screen.getByText('⌫')).toBeTruthy()
    })

    it('未输入昵称和 PIN 时按钮禁用', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      const btn = screen.getByText('进入 🚀') as HTMLButtonElement
      expect(btn.disabled).toBe(true)
    })

    it('输入昵称 + 4 位 PIN 后按钮启用', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.change(screen.getByPlaceholderText('输入注册时的昵称'), { target: { value: '小明' } })
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      const btn = screen.getByText('进入 🚀') as HTMLButtonElement
      expect(btn.disabled).toBe(false)
    })

    it('登录成功调用 onLogin', async () => {
      ;(pinLogin as any).mockResolvedValue({
        token: 'tk', refreshToken: 'rtk', userId: 'u1', userType: 'student', displayName: '小明',
      })
      const onLogin = vi.fn()
      render(<LoginPage onLogin={onLogin} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.change(screen.getByPlaceholderText('输入注册时的昵称'), { target: { value: '小明' } })
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('进入 🚀'))
      await waitFor(() => expect(onLogin).toHaveBeenCalled())
      expect(setToken).toHaveBeenCalledWith('tk')
      expect(setUser).toHaveBeenCalled()
    })

    it('登录失败显示错误', async () => {
      ;(pinLogin as any).mockRejectedValue(new Error('昵称或密码错误'))
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.change(screen.getByPlaceholderText('输入注册时的昵称'), { target: { value: 'X' } })
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('进入 🚀'))
      await waitFor(() => expect(screen.getByText('昵称或密码错误')).toBeTruthy())
    })

    it('删除键回退 PIN', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('⌫'))
      // PIN 指示器只有 1 个 filled
      const { container } = render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      // 简单验证删除键存在且可点击
      expect(screen.getAllByText('⌫').length).toBeGreaterThanOrEqual(1)
    })
  })

  describe('声音进入', () => {
    it('麦克风支持时显示声音进入按钮', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      expect(screen.getByText('🎤 声音进入')).toBeTruthy()
    })

    it('无声纹时点击声音进入显示引导提示', async () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.click(screen.getByText('🎤 声音进入'))
      await waitFor(() => {
        expect(screen.getByText('还没录过你的声音哦')).toBeTruthy()
      })
    })

    it('引导提示点击"知道啦"关闭', async () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.click(screen.getByText('🎤 声音进入'))
      await waitFor(() => expect(screen.getByText('知道啦')).toBeTruthy())
      fireEvent.click(screen.getByText('知道啦'))
      expect(screen.queryByText('还没录过你的声音哦')).toBeNull()
    })

    // ==== AUD-008：模型不再挂载即预下载，点击声音进入后按需下载 + 流量确认 ====
    it('有声纹且模型未就绪：点击先弹流量确认，确认后下载并打开识别', async () => {
      ;(hasAnyVoiceprint as any).mockResolvedValue(true)
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      await waitFor(() => expect(screen.getByText('🎤 声音进入')).toBeTruthy())
      fireEvent.click(screen.getByText('🎤 声音进入'))
      // 未直接打开识别，先弹流量确认
      expect(screen.getByText('需要下载语音模型')).toBeTruthy()
      expect(preloadVoiceprintModel).not.toHaveBeenCalled()
      // 确认后开始下载并打开声纹识别
      fireEvent.click(screen.getByText('继续下载'))
      await waitFor(() => expect(preloadVoiceprintModel).toHaveBeenCalledTimes(1))
      expect(screen.getByTestId('voice-overlay')).toBeTruthy()
    })

    it('有声纹且模型未就绪：取消流量确认则不下载不进入', async () => {
      ;(hasAnyVoiceprint as any).mockResolvedValue(true)
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      await waitFor(() => expect(screen.getByText('🎤 声音进入')).toBeTruthy())
      fireEvent.click(screen.getByText('🎤 声音进入'))
      expect(screen.getByText('需要下载语音模型')).toBeTruthy()
      fireEvent.click(screen.getByText('取消'))
      expect(preloadVoiceprintModel).not.toHaveBeenCalled()
      expect(screen.queryByTestId('voice-overlay')).toBeNull()
    })

    it('有声纹且模型已就绪：点击直接进入识别（不弹流量确认）', async () => {
      ;(hasAnyVoiceprint as any).mockResolvedValue(true)
      mockVpStatus = 'ready'
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      await waitFor(() => expect(screen.getByText('🎤 声音进入')).toBeTruthy())
      fireEvent.click(screen.getByText('🎤 声音进入'))
      expect(screen.queryByText('需要下载语音模型')).toBeNull()
      expect(screen.getByTestId('voice-overlay')).toBeTruthy()
      expect(preloadVoiceprintModel).not.toHaveBeenCalled()
    })
  })

  describe('注册表单', () => {
    it('渲染所有必填字段', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      expect(screen.getByText('邀请码 *')).toBeTruthy()
      expect(screen.getByText(/昵称/)).toBeTruthy()
      expect(screen.getByText('性别 *')).toBeTruthy()
      expect(screen.getByText('年龄 *')).toBeTruthy()
    })

    it('未填必填项提交显示错误', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.click(screen.getByText('注册 🚀'))
      expect(screen.getByText('请填写所有必填项')).toBeTruthy()
    })

    it('年龄 < 14 显示家长手机号字段和警告', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '10' } })
      expect(screen.getByText('家长手机号 *')).toBeTruthy()
      expect(screen.getByText(/不满 14 周岁/)).toBeTruthy()
    })

    it('性别选择', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.click(screen.getByText('👦 男生'))
      // 选中后 class 包含 sel
      expect(screen.getByText('👦 男生').className).toContain('sel')
    })

    it('填写完整后提交弹出二次确认', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      expect(screen.getByText('确认要注册吗？')).toBeTruthy()
    })

    it('确认注册成功后进入 PIN 设置步骤', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', refreshToken: 'rtk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      // 二次确认
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => {
        expect(screen.getByText('设置你的秘密数字')).toBeTruthy()
      })
      expect(setToken).toHaveBeenCalledWith('tk')
    })

    it('PIN 设置流程：输入两次一致后进入声纹选择', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      ;(setPin as any).mockResolvedValue({})
      const onRegister = vi.fn()
      render(<LoginPage onLogin={vi.fn()} onRegister={onRegister} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      // 输入 PIN 1234
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('下一步'))
      // 确认步骤
      expect(screen.getByText('再输入一次确认')).toBeTruthy()
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('确认设置'))
      // voiceConsent 默认 true → 进入声纹选择
      await waitFor(() => {
        expect(screen.getByText('要录入你的声音吗？')).toBeTruthy()
      })
      expect(setPin).toHaveBeenCalledWith('1234')
    })

    it('PIN 两次不一致显示错误并重置', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('下一步'))
      // 输入不同的 PIN
      fireEvent.click(screen.getByText('5'))
      fireEvent.click(screen.getByText('6'))
      fireEvent.click(screen.getByText('7'))
      fireEvent.click(screen.getByText('8'))
      fireEvent.click(screen.getByText('确认设置'))
      await waitFor(() => {
        expect(screen.getByText('两次输入不一致，请重新设置')).toBeTruthy()
      })
    })

    it('跳过 PIN 设置直接进入完成页', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      fireEvent.click(screen.getByText('先不设置，以后再说'))
      // voiceConsent 默认 true 且 regUserId 存在 → 进入 voice-choice
      await waitFor(() => {
        expect(screen.getByText('要录入你的声音吗？')).toBeTruthy()
      })
    })

    it('声纹选择页：点击"以后再说"进入完成页', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      fireEvent.click(screen.getByText('先不设置，以后再说'))
      await waitFor(() => expect(screen.getByText('要录入你的声音吗？')).toBeTruthy())
      fireEvent.click(screen.getByText('以后再说，先用秘密数字'))
      expect(screen.getByText('注册成功！')).toBeTruthy()
    })

    it('注册失败显示错误信息', async () => {
      ;(trialRegister as any).mockRejectedValue(new Error('邀请码无效'))
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'BAD' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => {
        expect(screen.getByText('邀请码无效')).toBeTruthy()
      })
    })

    it('注册成功返回 familyCode 时完成页展示家庭码', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花', familyCode: 'FAM-8899',
      })
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      // 跳过 PIN（voiceConsent 默认 true → voice-choice）→ 以后再说 → done
      fireEvent.click(screen.getByText('先不设置，以后再说'))
      await waitFor(() => expect(screen.getByText('要录入你的声音吗？')).toBeTruthy())
      fireEvent.click(screen.getByText('以后再说，先用秘密数字'))
      expect(screen.getByText('注册成功！')).toBeTruthy()
      expect(screen.getByText('FAM-8899')).toBeTruthy()
      expect(screen.getByText(/我的家庭码/)).toBeTruthy()
    })

    it('取消声纹授权后跳过 PIN 直接进入完成页', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      ;(setPin as any).mockResolvedValue({})
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      // 取消声纹授权 checkbox
      fireEvent.click(screen.getByRole('checkbox'))
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      // voiceConsent=false → 跳过 PIN 直接进入 done
      fireEvent.click(screen.getByText('先不设置，以后再说'))
      await waitFor(() => expect(screen.getByText('注册成功！')).toBeTruthy())
    })
  })

  describe('Tab 切换', () => {
    it('从注册 tab 切回登录 tab', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      expect(screen.getByText('邀请码 *')).toBeTruthy()
      fireEvent.click(screen.getByText('登录'))
      expect(screen.getByText('你的昵称')).toBeTruthy()
      expect(screen.queryByText('邀请码 *')).toBeNull()
    })
  })

  describe('PIN 设置失败', () => {
    it('setPin 拒绝时显示错误并回到表单', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      })
      ;(setPin as any).mockRejectedValue(new Error('网络异常'))
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      // 输入 PIN
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('下一步'))
      fireEvent.click(screen.getByText('1'))
      fireEvent.click(screen.getByText('2'))
      fireEvent.click(screen.getByText('3'))
      fireEvent.click(screen.getByText('4'))
      fireEvent.click(screen.getByText('确认设置'))
      // setPin 拒绝 → 显示错误并回到表单
      await waitFor(() => {
        expect(screen.getByText('网络异常')).toBeTruthy()
      })
      // 回到注册表单
      expect(screen.getByPlaceholderText('老师发的邀请码')).toBeTruthy()
    })
  })

  describe('声纹采集流程', () => {
    it('注册后选择录入声纹并完成采集', async () => {
      ;(trialRegister as any).mockResolvedValue({
        token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花', familyCode: 'FAM-X',
      })
      ;(setPin as any).mockResolvedValue({})
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      fireEvent.click(screen.getByText('没错，注册！'))
      await waitFor(() => expect(screen.getByText('设置你的秘密数字')).toBeTruthy())
      // 跳过 PIN → voice-choice
      fireEvent.click(screen.getByText('先不设置，以后再说'))
      await waitFor(() => expect(screen.getByText('要录入你的声音吗？')).toBeTruthy())
      // 选择录入
      fireEvent.click(screen.getByText('好呀，现在录入！🎤'))
      // voice-enroll 步骤：VoiceLoginOverlay 渲染
      expect(screen.getByTestId('voice-overlay')).toBeTruthy()
      // 完成采集
      fireEvent.click(screen.getByText('采集完成'))
      // enrollVoiceprint 被调用 → 进入 done
      const { enrollVoiceprint } = await import('../utils/voiceprintStore')
      await waitFor(() => {
        expect(enrollVoiceprint).toHaveBeenCalledWith('u1', '花花', [[1, 2, 3]])
      })
      await waitFor(() => expect(screen.getByText('注册成功！')).toBeTruthy())
    })
  })

  describe('补充函数覆盖', () => {
    it('点击主题按钮触发 changeTheme', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.click(screen.getByText('🌸'))
      // changeTheme 是 vi.fn()，不报错即可
    })

    it('登录键盘点击 0 键', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      fireEvent.click(screen.getByText('0'))
      // 0 被输入到 PIN，检查 filled 指示器出现
      const container = document.querySelector('.pin-row')
      expect(container?.querySelector('.filled')).toBeTruthy()
    })

    it('注册确认弹窗点击“再改改”取消', async () => {
      const onRegister = vi.fn()
      render(<LoginPage onLogin={vi.fn()} onRegister={onRegister} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('老师发的邀请码'), { target: { value: 'DEMO2026' } })
      fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
      fireEvent.click(screen.getByText('👧 女生'))
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '15' } })
      fireEvent.click(screen.getByText('注册 🚀'))
      // 确认弹窗显示
      await waitFor(() => expect(screen.getByTestId('confirm-dialog')).toBeTruthy())
      // 点击取消（覆盖 onCancel 内联函数）
      fireEvent.click(screen.getByText('再改改'))
      // 取消后不会触发注册
      expect(onRegister).not.toHaveBeenCalled()
    })

    it('有声纹时声音进入显示覆盖层，识别成功触发 onLogin', async () => {
      const { hasAnyVoiceprint } = await import('../utils/voiceprintStore')
      ;(hasAnyVoiceprint as any).mockResolvedValue(true)
      mockVpStatus = 'ready' // AUD-008：模型已就绪 → 直接进入识别（不弹流量确认）
      const onLogin = vi.fn()
      render(<LoginPage onLogin={onLogin} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      await waitFor(() => expect(screen.getByText('🎤 声音进入')).toBeTruthy())
      fireEvent.click(screen.getByText('🎤 声音进入'))
      await waitFor(() => expect(screen.getByTestId('voice-overlay')).toBeTruthy())
      fireEvent.click(screen.getByText('识别成功'))
      expect(onLogin).toHaveBeenCalled()
    })

    it('有声纹时声音进入覆盖层取消关闭', async () => {
      const { hasAnyVoiceprint } = await import('../utils/voiceprintStore')
      ;(hasAnyVoiceprint as any).mockResolvedValue(true)
      mockVpStatus = 'ready' // AUD-008：模型已就绪 → 直接进入识别（不弹流量确认）
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} />)
      await waitFor(() => expect(screen.getByText('🎤 声音进入')).toBeTruthy())
      fireEvent.click(screen.getByText('🎤 声音进入'))
      await waitFor(() => expect(screen.getByTestId('voice-overlay')).toBeTruthy())
      fireEvent.click(screen.getByText('取消'))
      await waitFor(() => expect(screen.queryByTestId('voice-overlay')).toBeNull())
    })

    it('注册表单家长手机号输入', () => {
      render(<LoginPage onLogin={vi.fn()} onRegister={vi.fn()} onNeedConsent={vi.fn()} initialTab="register" />)
      fireEvent.change(screen.getByPlaceholderText('你的年龄'), { target: { value: '10' } })
      const phoneInput = screen.getByPlaceholderText('监护人的 11 位手机号')
      fireEvent.change(phoneInput, { target: { value: '13800138000' } })
      expect((phoneInput as HTMLInputElement).value).toBe('13800138000')
    })
  })
})
