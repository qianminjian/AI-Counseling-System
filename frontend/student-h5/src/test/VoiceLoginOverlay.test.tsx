import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'

// 可控 mock（vi.hoisted 确保提升安全）
const {
  mockExtractEmbedding, mockVerify, mockGetVoiceprint, mockVoiceLogin,
  mockSetToken, mockSetRefreshToken, mockSetUser, mockSessionStop,
  mockGetVoiceprintConfig, mockGetRemoteTenantId, mockRemoteVerify,
  mockCreateMicSession,
} = vi.hoisted(() => ({
  mockExtractEmbedding: vi.fn(),
  mockVerify: vi.fn(),
  mockGetVoiceprint: vi.fn(),
  mockVoiceLogin: vi.fn(),
  mockSetToken: vi.fn(),
  mockSetRefreshToken: vi.fn(),
  mockSetUser: vi.fn(),
  mockSessionStop: vi.fn(),
  mockGetVoiceprintConfig: vi.fn(),
  mockGetRemoteTenantId: vi.fn(),
  mockRemoteVerify: vi.fn(),
  mockCreateMicSession: vi.fn(),
}))

vi.mock('../hooks/useVoiceprint', () => ({
  useVoiceprint: () => ({
    extractEmbedding: mockExtractEmbedding,
    verify: mockVerify,
    loading: false,
    modelErrorRef: { current: null },
  }),
}))
vi.mock('../utils/audioUnlock', () => ({
  unlockAudio: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../utils/micSession', () => ({
  createMicSession: (...args: any[]) => mockCreateMicSession(...args),
}))
vi.mock('../utils/voiceprintStore', () => ({
  getVoiceprint: (...args: any[]) => mockGetVoiceprint(...args),
  getRemoteVoiceprintTenantId: (...args: any[]) => mockGetRemoteTenantId(...args),
}))
vi.mock('../api', () => ({
  voiceLogin: (...args: any[]) => mockVoiceLogin(...args),
  setToken: (...args: any[]) => mockSetToken(...args),
  setRefreshToken: (...args: any[]) => mockSetRefreshToken(...args),
  setUser: (...args: any[]) => mockSetUser(...args),
  getVoiceprintConfig: (...args: any[]) => mockGetVoiceprintConfig(...args),
  remoteVoiceprintVerify: (...args: any[]) => mockRemoteVerify(...args),
}))

import VoiceLoginOverlay from '../components/VoiceLoginOverlay'

describe('VoiceLoginOverlay', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    // 默认 mock
    mockExtractEmbedding.mockResolvedValue(new Float32Array(192).fill(0.1))
    mockVerify.mockResolvedValue({ matched: true, userId: 'u1', pseudonym: '小明', score: 0.9 })
    mockGetVoiceprint.mockResolvedValue({ userId: 'u1', pseudonym: '小明', voiceCredential: 'cred-123' })
    mockVoiceLogin.mockResolvedValue({ token: 'tk', refreshToken: 'rtk', userId: 'u1', userType: 'student', displayName: '小明' })
    mockGetVoiceprintConfig.mockResolvedValue({ mode: 'local', enabled: true })
    mockGetRemoteTenantId.mockReturnValue('tenant-t1')
    mockRemoteVerify.mockResolvedValue({ matched: false })
    mockCreateMicSession.mockResolvedValue({
      engine: 'worklet',
      stop: mockSessionStop,
      ctx: { sampleRate: 16000 },
      stream: { getTracks: () => [{ stop: vi.fn() }] },
    })
    // mock SpeechSynthesisUtterance
    ;(window as any).SpeechSynthesisUtterance = vi.fn().mockImplementation((text: string) => ({
      text, lang: '', rate: 1, voice: null, onend: null, onerror: null,
    }))
    Object.defineProperty(window, 'speechSynthesis', {
      value: {
        cancel: vi.fn(),
        speak: vi.fn((utter: any) => { setTimeout(() => utter.onend?.(), 10) }),
        getVoices: () => [],
      },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染取消按钮（✕）和隐私提示', async () => {
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByText('✕')).toBeTruthy()
    expect(screen.getByText(/声音信息只保存在这台设备上/)).toBeTruthy()
  })

  it('点击 ✕ 调用 onCancel', async () => {
    const onCancel = vi.fn()
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={onCancel} />)
    fireEvent.click(screen.getByText('✕'))
    expect(onCancel).toHaveBeenCalled()
  })

  it('注册模式显示跳过按钮', async () => {
    render(<VoiceLoginOverlay mode="enroll" onComplete={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByText('先不录了，以后再说')).toBeTruthy()
    fireEvent.click(screen.getByText('先不录了，以后再说'))
  })

  it('初始化后显示第一段引导语（verify 模式）', async () => {
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getByText('嗨！我是波波，跟我打个招呼吧！')).toBeTruthy()
  })

  it('麦克风不可用时显示失败提示', async () => {
    ;(mockCreateMicSession as any).mockRejectedValue(new Error('denied'))
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getByText(/麦克风不可用/)).toBeTruthy()
    expect(screen.getByText('用秘密数字登录')).toBeTruthy()
  })

  it('进度指示器渲染正确数量的点', async () => {
    const { container } = render(<VoiceLoginOverlay mode="enroll" onComplete={vi.fn()} onCancel={vi.fn()} />)
    const dots = container.querySelectorAll('.rounded-full.transition-colors')
    expect(dots.length).toBeGreaterThanOrEqual(2)
  })

  it('verify 完整成功流程：声纹匹配 + 凭证换 token', async () => {
    const onComplete = vi.fn()
    render(<VoiceLoginOverlay mode="verify" onComplete={onComplete} onCancel={vi.fn()} />)
    // 推进整个流程：2 轮 × (8s speakPrompt + 4s captureSegment) + processing
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    // 验证调用了 extractEmbedding（2 轮）
    expect(mockExtractEmbedding).toHaveBeenCalledTimes(2)
    // 验证调用了 verify
    expect(mockVerify).toHaveBeenCalled()
    // 验证获取凭证并调用 voiceLogin
    expect(mockGetVoiceprint).toHaveBeenCalledWith('u1')
    expect(mockVoiceLogin).toHaveBeenCalledWith('cred-123')
    // 验证 token 设置
    expect(mockSetToken).toHaveBeenCalledWith('tk')
    expect(mockSetRefreshToken).toHaveBeenCalledWith('rtk')
    expect(mockSetUser).toHaveBeenCalled()
    // 成功文案
    expect(screen.getByText(/是小明呀！欢迎回来/)).toBeTruthy()
    // 1.5s 后 onComplete
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ matched: true, userId: 'u1' }))
  })

  it('verify 凭证缺失：显示 credential 失败提示', async () => {
    mockGetVoiceprint.mockResolvedValue({ userId: 'u1', pseudonym: '小明', voiceCredential: undefined })
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(screen.getByText(/登录钥匙还没办好/)).toBeTruthy()
    expect(screen.getByText('用秘密数字登录')).toBeTruthy()
  })

  it('verify 凭证过期（voiceLogin 抛异常）', async () => {
    mockVoiceLogin.mockRejectedValue(new Error('expired'))
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(screen.getByText(/登录钥匙过期啦/)).toBeTruthy()
  })

  it('remote 模式 verify：租户信息缺失 → 引导重录（AUD-001）', async () => {
    mockGetVoiceprintConfig.mockResolvedValue({ mode: 'remote', enabled: true })
    mockGetRemoteTenantId.mockReturnValue(null)
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(screen.getByText(/登录钥匙还没办好/)).toBeTruthy()
    // 未携带租户维度时禁止发起服务端比对
    expect(mockRemoteVerify).not.toHaveBeenCalled()
  })

  it('remote 模式 verify 成功：携带租户维度并签发 token（AUD-001）', async () => {
    mockGetVoiceprintConfig.mockResolvedValue({ mode: 'remote', enabled: true })
    mockRemoteVerify.mockResolvedValue({ matched: true, token: 'tk-r', refreshToken: 'rtk-r', userId: 'u1', userType: 'student', displayName: '小美' })
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    // 请求必须携带 embeddings + 服务端签发的 tenantId
    expect(mockRemoteVerify).toHaveBeenCalledWith(expect.any(Array), 'tenant-t1')
    expect(mockSetToken).toHaveBeenCalledWith('tk-r')
    expect(screen.getByText(/是小美呀！欢迎回来/)).toBeTruthy()
  })

  it('verify 声纹不匹配：显示重试按钮（第 1 次）', async () => {
    mockVerify.mockResolvedValue({ matched: false, score: 0.3 })
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(screen.getByText(/听起来不太像你/)).toBeTruthy()
    expect(screen.getByText('🎤 再试一次')).toBeTruthy()
  })

  it('verify 声纹不匹配 2 次后：引导 PIN 登录', async () => {
    mockVerify.mockResolvedValue({ matched: false, score: 0.3 })
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    // 第 1 轮失败
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    // 点击重试
    fireEvent.click(screen.getByText('🎤 再试一次'))
    // 第 2 轮失败
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(screen.getByText(/还是没认出来/)).toBeTruthy()
    // 不再显示重试按钮
    expect(screen.queryByText('🎤 再试一次')).toBeNull()
  })

  it('enroll 模式完整流程：采集成功返回 embeddings', async () => {
    const onComplete = vi.fn()
    render(<VoiceLoginOverlay mode="enroll" onComplete={onComplete} onCancel={vi.fn()} />)
    // 3 轮 × (8s + 4/5s)
    await act(async () => { await vi.advanceTimersByTimeAsync(45000) })
    expect(mockExtractEmbedding).toHaveBeenCalledTimes(3)
    expect(screen.getByText('声音录入成功！')).toBeTruthy()
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(onComplete).toHaveBeenCalledWith(expect.objectContaining({ matched: true }))
  })

  it('extractEmbedding 返回 null 时重试当前轮（不计入失败）', async () => {
    // 第一次返回 null（静音），第二次正常
    mockExtractEmbedding
      .mockResolvedValueOnce(null)
      .mockResolvedValue(new Float32Array(192).fill(0.1))
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    // 需要更多时间因为有一轮重试
    await act(async () => { await vi.advanceTimersByTimeAsync(50000) })
    // 至少调用了 3 次（1 次 null + 2 次正常）
    expect(mockExtractEmbedding.mock.calls.length).toBeGreaterThanOrEqual(3)
  })

  it('失败时点击“用秘密数字登录”调用 onCancel', async () => {
    ;(mockCreateMicSession as any).mockRejectedValue(new Error('denied'))
    const onCancel = vi.fn()
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={onCancel} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.click(screen.getByText('用秘密数字登录'))
    expect(onCancel).toHaveBeenCalled()
  })

  it('采集中显示音量动画条，识别中显示加载动画', async () => {
    // 让第一次 extractEmbedding 挂起，以便观察 processing 阶段
    let resolveExtract: (v: any) => void = () => {}
    mockExtractEmbedding.mockImplementationOnce(() => new Promise(r => { resolveExtract = r }))
    render(<VoiceLoginOverlay mode="verify" onComplete={vi.fn()} onCancel={vi.fn()} />)
    // 初始化（initMic 异步完成）
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    // 确认进入 speaking 阶段
    expect(screen.getByText('嗨！我是波波，跟我打个招呼吧！')).toBeTruthy()
    // speak onend 10ms → phase='listening'
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    expect(screen.getByText('对波波说“你好”就行')).toBeTruthy()
    // 音量动画条（12 个 bg-blue-400 元素）
    const bars = document.querySelectorAll('.bg-blue-400')
    expect(bars.length).toBe(12)
    // captureSegment 4s → phase='processing'（extract 挂起中）
    await act(async () => { await vi.advanceTimersByTimeAsync(4100) })
    expect(screen.getByText('正在识别...')).toBeTruthy()
    expect(document.querySelector('.animate-spin')).toBeTruthy()
    // 释放 extract，让流程完成
    await act(async () => {
      resolveExtract(new Float32Array(192).fill(0.1))
      await vi.advanceTimersByTimeAsync(30000)
    })
  })
})
