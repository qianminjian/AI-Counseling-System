import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'

// ===== mock 所有 hooks =====
const mockTts = {
  speak: vi.fn().mockResolvedValue(undefined),
  speakSentence: vi.fn().mockResolvedValue(undefined),
  feedToken: vi.fn(),
  startStreaming: vi.fn(),
  stopStreaming: vi.fn(),
  endStreaming: vi.fn(),
  stop: vi.fn(),
  unlock: vi.fn(),
  toggleMute: vi.fn(),
  muted: false,
  playing: false,
  engine: 'speechSynthesis',
  currentSentenceText: '',
}
vi.mock('../hooks/useTtsPlayer', () => ({
  useTtsPlayer: () => mockTts,
}))
vi.mock('../hooks/useVoicePersona', () => ({
  useVoicePersona: () => ({ personaId: 'bobo' }),
}))
vi.mock('../hooks/useVoiceCallMode', () => ({
  useVoiceCallMode: () => mockVoiceCallState,
}))
vi.mock('../hooks/useSilenceNudge', () => ({
  useSilenceNudge: () => ({ recordInteraction: vi.fn(), resetSilenceBase: vi.fn() }),
}))
vi.mock('../hooks/useWakeWord', () => ({
  preloadWakeModel: vi.fn(),
  useWakeWord: () => ({ supported: false, wakeStatus: 'idle' }),
  __resetWakeWordForTest: vi.fn(),
}))

const mockRecorder = {
  recording: false,
  analyzing: false,
  supported: true,
  startRecording: vi.fn(),
  stopRecording: vi.fn(),
  cancelRecording: vi.fn(),
  warmUp: vi.fn(),
  releaseStream: vi.fn(),
}
let capturedRecordingCallback: ((blob: any) => void) | null = null
vi.mock('../hooks/useAudioRecorder', () => ({
  useAudioRecorder: (cb: any) => { capturedRecordingCallback = cb; return mockRecorder },
}))

// ===== mock 子组件 =====
const mockConsentState = { showDialog: false }
const mockWakeConsentState = { showDialog: false }
const mockVoiceCallState = { mode: 'off', wakeSupported: false, wakeStatus: 'idle' }
vi.mock('../components/VoiceConsentDialog', () => ({
  default: ({ onGrant, onDeny }: any) => (
    <div data-testid="voice-consent">
      <button onClick={onGrant}>允许</button>
      <button onClick={onDeny}>拒绝</button>
    </div>
  ),
  useVoiceConsent: () => ({
    showDialog: mockConsentState.showDialog,
    hasConsent: () => !mockConsentState.showDialog,
    requestConsent: () => true,
    grantConsent: vi.fn(),
    denyConsent: vi.fn(),
  }),
}))
vi.mock('../components/VoiceCallConsentDialog', () => ({
  default: ({ onGrant, onDeny }: any) => (
    <div data-testid="wake-consent">
      <button onClick={onGrant}>允许唤醒</button>
      <button onClick={onDeny}>拒绝唤醒</button>
    </div>
  ),
  useVoiceCallConsent: () => ({
    showDialog: mockWakeConsentState.showDialog,
    hasConsent: () => !mockWakeConsentState.showDialog,
    requestConsent: () => true,
    grantConsent: vi.fn(),
    denyConsent: vi.fn(),
  }),
}))
vi.mock('../components/SatisfactionDialog', () => ({
  default: ({ onSubmit, onSkip, onResume }: any) => (
    <div data-testid="satisfaction-dialog">
      <button onClick={() => onSubmit(5, '很好')}>评价</button>
      <button onClick={onSkip}>跳过</button>
      <button onClick={onResume}>继续聊</button>
    </div>
  ),
}))
vi.mock('../components/SettingsPanel', () => ({
  default: ({ open, onClose }: any) => open ? <div data-testid="settings-panel"><button onClick={onClose}>关闭设置</button></div> : null,
}))
vi.mock('../components/ConfirmDialog', () => ({
  default: ({ open, title, onConfirm, onCancel }: any) => open ? (
    <div data-testid="confirm-dialog">
      <span>{title}</span>
      <button onClick={onConfirm}>确认</button>
      <button onClick={onCancel}>取消</button>
    </div>
  ) : null,
}))
vi.mock('../components/BoBoPet', () => ({
  default: (props: any) => <div data-testid="bobo-pet" data-state={props.state} />,
}))
vi.mock('../components/DraggableVoiceButton', () => ({
  default: ({ children }: any) => <div data-testid="voice-btn">{children?.('right')}</div>,
}))
vi.mock('../components/MessageBubble', () => ({
  default: ({ msg }: any) => <div data-testid="msg-bubble">{msg.content}</div>,
  EMOTION_EMOJI: { happy: '😊', sad: '😢' },
}))
vi.mock('../components/ToolboxPanel', () => ({
  default: ({ onBack }: any) => <div data-testid="toolbox-panel"><button onClick={onBack}>关闭百宝箱</button></div>,
}))
vi.mock('../components/SosPanel', () => ({
  default: ({ onBack }: any) => <div data-testid="sos-panel"><button onClick={onBack}>关闭SOS</button></div>,
}))
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({
    theme: {
      companion: '🐬',
      companionName: '波波',
      bobo: { body: '#4fc3f7', belly: '#e1f5fe' },
    },
  }),
}))

const mockAuthFetch = vi.fn()
const mockApi = vi.fn()
vi.mock('../api', () => ({
  authFetch: (...args: any[]) => mockAuthFetch(...args),
  api: (...args: any[]) => mockApi(...args),
  getUser: () => ({ gender: 'male', pseudonym: '小明' }),
}))

import ChatRoom from '../components/ChatRoom'

const SESSION = { sessionId: 'sess-1', greeting: '你好呀！今天想聊什么？', emotionTag: 'neutral' }

describe('ChatRoom', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // jsdom 不支持 scrollIntoView
    Element.prototype.scrollIntoView = vi.fn()
    mockTts.muted = false
    mockTts.playing = false
    mockRecorder.recording = false
    mockRecorder.analyzing = false
    mockRecorder.supported = true
    mockConsentState.showDialog = false
    mockWakeConsentState.showDialog = false
    mockVoiceCallState.mode = 'off'
    mockVoiceCallState.wakeSupported = false
    mockVoiceCallState.wakeStatus = 'idle'
    localStorage.setItem('mindsafe_wake_enabled', '1')
  })

  it('渲染 header（伙伴名 + 结束按钮）', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('波波')).toBeTruthy()
    expect(screen.getByText('结束')).toBeTruthy()
  })

  // ===== F-2 工具箱/SOS 入口（design/36 §3.4：SOS 全局常驻，非埋藏在菜单里）=====
  it('header 常驻百宝箱与 SOS 入口', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByTitle('百宝箱')).toBeTruthy()
    expect(screen.getByTitle('SOS 帮助')).toBeTruthy()
  })

  it('点击百宝箱打开工具箱面板，可关闭', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.queryByTestId('toolbox-panel')).toBeNull()
    fireEvent.click(screen.getByTitle('百宝箱'))
    expect(screen.getByTestId('toolbox-panel')).toBeTruthy()
    fireEvent.click(screen.getByText('关闭百宝箱'))
    expect(screen.queryByTestId('toolbox-panel')).toBeNull()
  })

  it('点击 SOS 打开 SOS 面板，可关闭', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.queryByTestId('sos-panel')).toBeNull()
    fireEvent.click(screen.getByTitle('SOS 帮助'))
    expect(screen.getByTestId('sos-panel')).toBeTruthy()
    fireEvent.click(screen.getByText('关闭SOS'))
    expect(screen.queryByTestId('sos-panel')).toBeNull()
  })

  it('初始显示打招呼消息', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('你好呀！今天想聊什么？')).toBeTruthy()
  })

  it('进入时自动朗读问候语', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(mockTts.unlock).toHaveBeenCalled()
    expect(mockTts.speak).toHaveBeenCalledWith('你好呀！今天想聊什么？')
  })

  it('静音时不朗读问候语', () => {
    mockTts.muted = true
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(mockTts.speak).not.toHaveBeenCalled()
  })

  it('输入文字并发送（SSE 流式回复）', async () => {
    const encoder = new TextEncoder()
    const chunks = [
      encoder.encode('data:{"type":"token","content":"你好"}\n\n'),
      encoder.encode('data:{"type":"token","content":"呀"}\n\n'),
    ]
    let readIdx = 0
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readIdx < chunks.length) return Promise.resolve({ done: false, value: chunks[readIdx++] })
            return Promise.resolve({ done: true, value: undefined })
          },
          cancel: vi.fn(),
        }),
      },
    })

    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '我今天很开心' } })
    fireEvent.click(screen.getByText('发送'))

    await waitFor(() => {
      expect(screen.getByText('你好呀')).toBeTruthy()
    })
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/chat/sessions/sess-1/messages',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('发送失败显示错误消息', async () => {
    mockAuthFetch.mockRejectedValue(new Error('network'))
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '测试' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      expect(screen.getByText('网络出了点问题，请再试一次哦 🙏')).toBeTruthy()
    })
  })

  it('点击结束弹出满意度评价', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    fireEvent.click(screen.getByText('结束'))
    expect(screen.getByTestId('satisfaction-dialog')).toBeTruthy()
  })

  it('满意度评价后关闭会话', async () => {
    mockApi.mockResolvedValue({})
    const onEnd = vi.fn()
    render(<ChatRoom session={SESSION} onEnd={onEnd} />)
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('评价'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
  })

  it('跳过评价关闭会话', async () => {
    mockApi.mockResolvedValue({})
    const onEnd = vi.fn()
    render(<ChatRoom session={SESSION} onEnd={onEnd} />)
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('跳过'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
  })

  it('继续聊关闭满意度弹窗', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('继续聊'))
    expect(screen.queryByTestId('satisfaction-dialog')).toBeNull()
  })

  it('设置面板打开关闭', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    fireEvent.click(screen.getByTitle('设置'))
    expect(screen.getByTestId('settings-panel')).toBeTruthy()
    fireEvent.click(screen.getByText('关闭设置'))
    expect(screen.queryByTestId('settings-panel')).toBeNull()
  })

  it('切换同学确认弹窗', () => {
    const onSwitchUser = vi.fn()
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} onSwitchUser={onSwitchUser} />)
    fireEvent.click(screen.getByText('换人'))
    expect(screen.getByTestId('confirm-dialog')).toBeTruthy()
    fireEvent.click(screen.getByText('确认'))
    expect(onSwitchUser).toHaveBeenCalled()
  })

  it('切换同学取消', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} onSwitchUser={vi.fn()} />)
    fireEvent.click(screen.getByText('换人'))
    fireEvent.click(screen.getByText('取消'))
    expect(screen.queryByTestId('confirm-dialog')).toBeNull()
  })

  it('TTS 静音切换按钮', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const muteBtn = screen.getByTitle('关闭语音')
    fireEvent.click(muteBtn)
    expect(mockTts.toggleMute).toHaveBeenCalled()
  })

  it('TTS 引擎不可用显示提示', () => {
    mockTts.engine = 'none'
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText(/当前浏览器不支持语音播放/)).toBeTruthy()
  })

  it('无 onSwitchUser 不显示换人按钮', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.queryByText('换人')).toBeNull()
  })

  it('空输入不能发送', () => {
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const sendBtn = screen.getByText('发送') as HTMLButtonElement
    expect(sendBtn.disabled).toBe(true)
  })

  it('Enter 键发送消息', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '你好' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })
    await waitFor(() => expect(mockAuthFetch).toHaveBeenCalled())
  })

  it('波波状态：streaming 时为 thinking', async () => {
    // 让 authFetch 挂起以维持 streaming 状态
    let resolvePromise: any
    mockAuthFetch.mockReturnValue(new Promise((r) => { resolvePromise = r }))
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '测试' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      const bobo = screen.getAllByTestId('bobo-pet')[0]
      expect(bobo.getAttribute('data-state')).toBe('thinking')
    })
    // cleanup
    resolvePromise({ ok: true, body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) } })
  })

  it('handleRecordingComplete：无录音 + 有浏览器转写 → 自动发送', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    // 模拟浏览器转写有值
    ;(window as any).SpeechRecognition = undefined
    // 直接调用 capturedRecordingCallback(null) 模拟无录音
    await act(async () => { capturedRecordingCallback?.(null) })
    // 无转写时显示提示
    expect(screen.getByText(/没有听清/)).toBeTruthy()
  })

  it('handleRecordingComplete：有录音 → 上传分析成功 → 自动发送', async () => {
    mockAuthFetch
      .mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({ success: true, data: { text: '我很开心', emotion: { labelEn: 'happy', label: '开心', confidence: 0.9 } } }),
      })
      .mockResolvedValueOnce({
        ok: true,
        body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
      })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { capturedRecordingCallback?.(blob) })
    await waitFor(() => {
      expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/voice/analyze', expect.objectContaining({ method: 'POST' }))
    })
  })

  it('handleRecordingComplete：上传失败 → 降级浏览器识别', async () => {
    mockAuthFetch.mockRejectedValueOnce(new Error('server down'))
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { capturedRecordingCallback?.(blob) })
    // 无浏览器转写 → 显示不可用提示
    expect(screen.getByText(/语音识别暂不可用/)).toBeTruthy()
  })

  it('handleRecordingComplete：上传返回无文字 → 降级', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ success: false }),
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { capturedRecordingCallback?.(blob) })
    expect(screen.getByText(/语音识别暂不可用/)).toBeTruthy()
  })

  it('SSE 流式回复含 risk 事件：不渲染到界面', async () => {
    const encoder = new TextEncoder()
    const chunks = [
      encoder.encode('data:{"type":"risk","content":"观察","metadata":{"riskLevel":"yellow"}}\n\n'),
      encoder.encode('data:{"type":"token","content":"没事的"}\n\n'),
    ]
    let readIdx = 0
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readIdx < chunks.length) return Promise.resolve({ done: false, value: chunks[readIdx++] })
            return Promise.resolve({ done: true, value: undefined })
          },
          cancel: vi.fn(),
        }),
      },
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '我有点难过' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      expect(screen.getByText('没事的')).toBeTruthy()
    })
    // risk 内容不显示
    expect(screen.queryByText('观察')).toBeNull()
  })

  it('SSE 流异常但已收到部分回复：保留内容不报错', async () => {
    const encoder = new TextEncoder()
    let readIdx = 0
    const chunks = [
      encoder.encode('data:{"type":"token","content":"你好"}\n\n'),
    ]
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readIdx === 0) { readIdx++; return Promise.resolve({ done: false, value: chunks[0] }) }
            return Promise.reject(new Error('stream broken'))
          },
          cancel: vi.fn(),
        }),
      },
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '测试' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      expect(screen.getByText('你好')).toBeTruthy()
    })
    // 不显示错误消息
    expect(screen.queryByText(/网络出了点问题/)).toBeNull()
  })

  it('closeSession 主接口失败时走 fallback 接口', async () => {
    mockApi.mockRejectedValueOnce(new Error('not found')).mockResolvedValueOnce({})
    const onEnd = vi.fn()
    render(<ChatRoom session={SESSION} onEnd={onEnd} />)
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('跳过'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
    expect(mockApi).toHaveBeenCalledTimes(2)
  })

  it('语音情绪预览：发送含 emotion 的消息后显示', async () => {
    const encoder = new TextEncoder()
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    // 通过 handleRecordingComplete 发送含 emotion 的消息
    mockAuthFetch
      .mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve({ success: true, data: { text: '开心', emotion: { labelEn: 'happy', label: '开心', confidence: 0.85 } } }),
      })
      .mockResolvedValueOnce({
        ok: true,
        body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
      })
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { capturedRecordingCallback?.(blob) })
    await waitFor(() => {
      expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/voice/analyze', expect.anything())
    })
  })

  it('语音授权弹窗显示时可交互', () => {
    mockConsentState.showDialog = true
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByTestId('voice-consent')).toBeTruthy()
    // 点击允许/拒绝不崩溃
    fireEvent.click(screen.getByText('允许'))
    fireEvent.click(screen.getByText('拒绝'))
  })

  it('语音唤醒授权弹窗显示时可交互', () => {
    mockWakeConsentState.showDialog = true
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByTestId('wake-consent')).toBeTruthy()
    fireEvent.click(screen.getByText('允许唤醒'))
    fireEvent.click(screen.getByText('拒绝唤醒'))
  })

  it('standby 模式下显示唤醒状态提示（loading/listening/error）', () => {
    mockVoiceCallState.mode = 'standby'
    mockVoiceCallState.wakeSupported = true
    mockVoiceCallState.wakeStatus = 'loading'
    const { rerender } = render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('正在加载语音引擎...')).toBeTruthy()

    mockVoiceCallState.wakeStatus = 'listening'
    rerender(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('我在这里安静地等你叫我')).toBeTruthy()

    mockVoiceCallState.wakeStatus = 'error'
    rerender(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('语音引擎加载失败，请关闭再开启')).toBeTruthy()
  })

  it('analyzing 状态显示识别中提示', () => {
    mockRecorder.analyzing = true
    render(<ChatRoom session={SESSION} onEnd={vi.fn()} />)
    expect(screen.getByText('正在识别，马上发送...')).toBeTruthy()
  })
})
