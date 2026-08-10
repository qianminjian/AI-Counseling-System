import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { ThemeProvider } from '../theme/ThemeProvider'

// ===== mock 1: useTtsPlayer =====
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

// ===== mock 2: useVoiceCallMode（唤醒状态机，与 ChatRoom 解耦） =====
const mockVoiceCallState = { mode: 'off', wakeSupported: false, wakeStatus: 'idle' }
vi.mock('../hooks/useVoiceCallMode', () => ({
  useVoiceCallMode: () => mockVoiceCallState,
}))

// ===== mock 3: useSilenceNudge =====
vi.mock('../hooks/useSilenceNudge', () => ({
  useSilenceNudge: () => ({ recordInteraction: vi.fn(), resetSilenceBase: vi.fn() }),
}))

// ===== mock 4: useWakeWord（预加载模型） =====
vi.mock('../hooks/useWakeWord', () => ({
  preloadWakeModel: vi.fn(),
  useWakeWord: () => ({ supported: false, wakeStatus: 'idle' }),
  useWakeModelStatus: () => ({ status: 'idle', progress: 0, error: '' }), // F-22：ModelDownloadProgress 消费
  __resetWakeWordForTest: vi.fn(),
}))

// ===== mock 5: useVoiceInputPipeline（ARCH-006：语音编排链已抽离，ChatRoom 只装配不实现） =====
const mockPipeline = {
  isRecording: false,
  isAnalyzing: false,
  isSending: false,
  supported: true,
  error: null,
  liveTranscript: '',
  warmUp: vi.fn(),
  releaseStream: vi.fn(),
  start: vi.fn(),
  stop: vi.fn(),
  cancel: vi.fn(),
}
let capturedOnTranscription: ((text: string, emotion: any) => void) | null = null
vi.mock('../hooks/useVoiceInputPipeline', () => ({
  useVoiceInputPipeline: ({ onTranscription }: any) => {
    capturedOnTranscription = onTranscription
    return mockPipeline
  },
}))

// ===== mock 6: api（fetchVoiceAnalyze 已由 pipeline 接管；ConsentKeys 供真实授权弹窗引用） =====
const mockAuthFetch = vi.fn()
const mockApi = vi.fn()
const mockFetchToolboxTools = vi.fn()
vi.mock('../api', () => ({
  authFetch: (...args: any[]) => mockAuthFetch(...args),
  api: (...args: any[]) => mockApi(...args),
  getUser: () => ({ gender: 'male', pseudonym: '小明' }),
  fetchToolboxTools: (...args: any[]) => mockFetchToolboxTools(...args),
  reportSosEvent: () => Promise.resolve(undefined),
  ConsentKeys: {
    NOTICE: 'mindsafe_consent_v1',
    VOICE: 'mindsafe_voice_consent_v1',
    VOICE_CALL: 'mindsafe_voicecall_consent_v1',
  },
}))

// ===== mock 7: BoBoPet（保留 mock：无外层 data-testid 且需断言 data-state；透传指针事件供装配测试） =====
vi.mock('../components/BoBoPet', () => ({
  default: (props: any) => (
    <div
      data-testid="bobo-pet"
      data-state={props.state}
      onPointerDown={props.onPointerDown}
      onPointerMove={props.onPointerMove}
      onPointerUp={props.onPointerUp}
      onPointerCancel={props.onPointerCancel}
    />
  ),
}))

// ===== mock 8: SettingsPanel（真实组件含 IndexedDB 声纹检查，保留 mock） =====
vi.mock('../components/SettingsPanel', () => ({
  default: ({ open, onClose }: any) => open ? <div data-testid="settings-panel"><button onClick={onClose}>关闭设置</button></div> : null,
}))

// 真实化组件（ARCH-006 §3.2）：useVoicePersona / VoiceConsentDialog / VoiceCallConsentDialog /
// SatisfactionDialog / ConfirmDialog / DraggableVoiceButton / MessageBubble / ToolboxPanel / SosPanel / ThemeProvider

import ChatRoom from '../components/ChatRoom'

const SESSION = { sessionId: 'sess-1', greeting: '你好呀！今天想聊什么？', emotionTag: 'neutral' }

const renderChatRoom = (props: Record<string, unknown> = {}) => {
  const utils = render(
    <ThemeProvider>
      <ChatRoom session={SESSION} onEnd={vi.fn()} {...props} />
    </ThemeProvider>
  )
  return {
    ...utils,
    rerenderChatRoom: (nextProps: Record<string, unknown> = {}) =>
      utils.rerender(
        <ThemeProvider>
          <ChatRoom session={SESSION} onEnd={vi.fn()} {...nextProps} />
        </ThemeProvider>
      ),
  }
}

describe('ChatRoom', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    // jsdom 不支持 scrollIntoView / setPointerCapture
    Element.prototype.scrollIntoView = vi.fn()
    Element.prototype.setPointerCapture = vi.fn()
    mockTts.muted = false
    mockTts.playing = false
    mockPipeline.isRecording = false
    mockPipeline.isAnalyzing = false
    mockPipeline.isSending = false
    mockPipeline.supported = true
    mockPipeline.error = null
    mockPipeline.liveTranscript = ''
    capturedOnTranscription = null
    mockVoiceCallState.mode = 'off'
    mockVoiceCallState.wakeSupported = false
    mockVoiceCallState.wakeStatus = 'idle'
    mockFetchToolboxTools.mockResolvedValue([])
    mockApi.mockResolvedValue({})
    // 唤醒默认开启 + 默认已授权（避免 800ms 自动授权弹窗干扰；授权弹窗用例自行 removeItem）
    localStorage.setItem('mindsafe_wake_enabled', '1')
    localStorage.setItem('mindsafe_voicecall_consent_v1', 'granted')
  })

  it('渲染 header（伙伴名 + 结束按钮）', () => {
    renderChatRoom()
    expect(screen.getByText('波波')).toBeTruthy()
    expect(screen.getByText('结束')).toBeTruthy()
  })

  // ===== F-2 工具箱/SOS 入口（design/36 §3.4：SOS 全局常驻，非埋藏在菜单里）=====
  it('header 常驻百宝箱与 SOS 入口', () => {
    renderChatRoom()
    expect(screen.getByTitle('百宝箱')).toBeTruthy()
    expect(screen.getByTitle('SOS 帮助')).toBeTruthy()
  })

  it('点击百宝箱打开工具箱面板，可关闭', () => {
    renderChatRoom()
    expect(screen.queryByText('百宝箱 🧰')).toBeNull()
    fireEvent.click(screen.getByTitle('百宝箱'))
    expect(screen.getByText('百宝箱 🧰')).toBeTruthy()
    fireEvent.click(screen.getAllByText('← 返回')[0])
    expect(screen.queryByText('百宝箱 🧰')).toBeNull()
  })

  it('点击 SOS 打开 SOS 面板，可关闭', () => {
    renderChatRoom()
    expect(screen.queryByText('波波在这里陪你 💙')).toBeNull()
    fireEvent.click(screen.getByTitle('SOS 帮助'))
    expect(screen.getByText('波波在这里陪你 💙')).toBeTruthy()
    fireEvent.click(screen.getAllByText('← 返回')[0])
    expect(screen.queryByText('波波在这里陪你 💙')).toBeNull()
  })

  it('初始显示打招呼消息', () => {
    renderChatRoom()
    expect(screen.getByText('你好呀！今天想聊什么？')).toBeTruthy()
  })

  it('进入时自动朗读问候语', () => {
    renderChatRoom()
    expect(mockTts.unlock).toHaveBeenCalled()
    expect(mockTts.speak).toHaveBeenCalledWith('你好呀！今天想聊什么？')
  })

  it('静音时不朗读问候语', () => {
    mockTts.muted = true
    renderChatRoom()
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

    renderChatRoom()
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
    renderChatRoom()
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '测试' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      expect(screen.getByText('网络出了点问题，请再试一次哦 🙏')).toBeTruthy()
    })
  })

  it('点击结束弹出满意度评价', () => {
    renderChatRoom()
    fireEvent.click(screen.getByText('结束'))
    expect(screen.getByText('今天的聊天对你有帮助吗？')).toBeTruthy()
  })

  it('满意度评价后关闭会话', async () => {
    mockApi.mockResolvedValue({})
    const onEnd = vi.fn()
    renderChatRoom({ onEnd })
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('🥰'))
    fireEvent.click(screen.getByText('提交'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
  })

  it('跳过评价关闭会话', async () => {
    mockApi.mockResolvedValue({})
    const onEnd = vi.fn()
    renderChatRoom({ onEnd })
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('跳过'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
  })

  it('继续聊关闭满意度弹窗', () => {
    renderChatRoom()
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText(/再聊一会儿/))
    expect(screen.queryByText('今天的聊天对你有帮助吗？')).toBeNull()
  })

  it('设置面板打开关闭', () => {
    renderChatRoom()
    fireEvent.click(screen.getByTitle('设置'))
    expect(screen.getByTestId('settings-panel')).toBeTruthy()
    fireEvent.click(screen.getByText('关闭设置'))
    expect(screen.queryByTestId('settings-panel')).toBeNull()
  })

  it('切换同学确认弹窗', () => {
    const onSwitchUser = vi.fn()
    renderChatRoom({ onSwitchUser })
    fireEvent.click(screen.getByText('换人'))
    expect(screen.getByText('要退出让别的同学用吗？')).toBeTruthy()
    fireEvent.click(screen.getByText('确认退出'))
    expect(onSwitchUser).toHaveBeenCalled()
  })

  it('切换同学取消', () => {
    renderChatRoom({ onSwitchUser: vi.fn() })
    fireEvent.click(screen.getByText('换人'))
    fireEvent.click(screen.getByText('我点错了'))
    expect(screen.queryByText('要退出让别的同学用吗？')).toBeNull()
  })

  it('TTS 静音切换按钮', () => {
    renderChatRoom()
    const muteBtn = screen.getByTitle('关闭语音')
    fireEvent.click(muteBtn)
    expect(mockTts.toggleMute).toHaveBeenCalled()
  })

  it('TTS 引擎不可用显示提示', () => {
    mockTts.engine = 'none'
    renderChatRoom()
    expect(screen.getByText(/当前浏览器不支持语音播放/)).toBeTruthy()
  })

  it('无 onSwitchUser 不显示换人按钮', () => {
    renderChatRoom()
    expect(screen.queryByText('换人')).toBeNull()
  })

  it('空输入不能发送', () => {
    renderChatRoom()
    const sendBtn = screen.getByText('发送') as HTMLButtonElement
    expect(sendBtn.disabled).toBe(true)
  })

  it('Enter 键发送消息', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    renderChatRoom()
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '你好' } })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false })
    await waitFor(() => expect(mockAuthFetch).toHaveBeenCalled())
  })

  it('波波状态：streaming 时为 thinking', async () => {
    // 让 authFetch 挂起以维持 streaming 状态
    let resolvePromise: any
    mockAuthFetch.mockReturnValue(new Promise((r) => { resolvePromise = r }))
    renderChatRoom()
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

  // ===== ARCH-006：语音编排黑盒化——错误提示 / 自动发送 / 装配绑定 =====

  it('pipeline error（无转写）→ 显示"没有听清"提示', () => {
    mockPipeline.error = '没有听清，请再说一次或打字告诉我 ✏️'
    renderChatRoom()
    expect(screen.getByText(/没有听清/)).toBeTruthy()
  })

  it('pipeline error（分析失败无转写）→ 显示语音识别不可用提示', () => {
    mockPipeline.error = '语音识别暂不可用，请打字告诉我吧 ✏️'
    renderChatRoom()
    expect(screen.getByText(/语音识别暂不可用/)).toBeTruthy()
  })

  it('pipeline error（降级浏览器识别）→ 显示降级提示', () => {
    mockPipeline.error = '已用浏览器识别（语音情绪分析暂不可用）'
    renderChatRoom()
    expect(screen.getByText(/已用浏览器识别/)).toBeTruthy()
  })

  it('pipeline onTranscription（分析成功）→ 自动发送', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    renderChatRoom()
    await act(async () => {
      capturedOnTranscription?.('我很开心', { labelEn: 'happy', label: '开心', confidence: 0.9 })
    })
    await waitFor(() => {
      expect(mockAuthFetch).toHaveBeenCalledWith(
        '/api/v1/chat/sessions/sess-1/messages',
        expect.objectContaining({ method: 'POST' })
      )
    })
  })

  it('语音情绪预览：onTranscription 带 emotion → 用户消息显示情绪 emoji', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: { getReader: () => ({ read: () => Promise.resolve({ done: true }), cancel: vi.fn() }) },
    })
    renderChatRoom()
    await act(async () => {
      capturedOnTranscription?.('开心', { labelEn: 'happy', label: '开心', confidence: 0.85 })
    })
    await waitFor(() => {
      // 真实 MessageBubble：user 消息带 happy 情绪 → 气泡前显示 😊
      expect(screen.getByText('😊')).toBeTruthy()
    })
  })

  it('按住说话：按下 start / 正常松手 stop', () => {
    localStorage.setItem('mindsafe_voice_consent_v1', 'granted')
    const { rerenderChatRoom } = renderChatRoom()
    const bobo = screen.getAllByTestId('bobo-pet')[0]
    fireEvent.pointerDown(bobo, { pointerId: 1, clientY: 200 })
    expect(mockPipeline.start).toHaveBeenCalledTimes(1)
    mockPipeline.isRecording = true // 模拟录音开始
    rerenderChatRoom()
    fireEvent.pointerUp(bobo, { pointerId: 1, clientY: 200 })
    expect(mockPipeline.stop).toHaveBeenCalledTimes(1)
  })

  it('按住说话：上滑取消 → cancel + "已取消"提示', () => {
    localStorage.setItem('mindsafe_voice_consent_v1', 'granted')
    const { rerenderChatRoom } = renderChatRoom()
    const bobo = screen.getAllByTestId('bobo-pet')[0]
    fireEvent.pointerDown(bobo, { pointerId: 1, clientY: 200 })
    mockPipeline.isRecording = true // 模拟录音开始
    rerenderChatRoom()
    fireEvent.pointerMove(bobo, { pointerId: 1, clientY: 100 }) // 上滑 100px > 60 阈值
    fireEvent.pointerUp(bobo, { pointerId: 1, clientY: 100 })
    expect(mockPipeline.cancel).toHaveBeenCalledTimes(1)
    expect(mockPipeline.stop).not.toHaveBeenCalled()
    expect(screen.getByText('已取消')).toBeTruthy()
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
    renderChatRoom()
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
    renderChatRoom()
    const input = screen.getByPlaceholderText('也可以打字告诉我')
    fireEvent.change(input, { target: { value: '测试' } })
    fireEvent.click(screen.getByText('发送'))
    await waitFor(() => {
      expect(screen.getByText('你好')).toBeTruthy()
    })
    // 不显示错误消息
    expect(screen.queryByText(/网络出了点问题/)).toBeNull()
  })

  it('closeSession 主接口失败 → 不回退旧接口，UI 关闭流程照常（ARCH-010 D5）', async () => {
    mockApi.mockRejectedValue(new Error('not found'))
    const onEnd = vi.fn()
    renderChatRoom({ onEnd })
    fireEvent.click(screen.getByText('结束'))
    fireEvent.click(screen.getByText('跳过'))
    await waitFor(() => expect(onEnd).toHaveBeenCalled())
    expect(mockApi).toHaveBeenCalledTimes(1)
  })

  // ===== 授权弹窗（真实组件，操作 localStorage） =====

  it('语音授权弹窗：未授权按下 → 弹出，同意后授权写入可直录', () => {
    renderChatRoom()
    const bobo = screen.getAllByTestId('bobo-pet')[0]
    fireEvent.pointerDown(bobo, { pointerId: 1, clientY: 200 })
    expect(screen.getByText('语音功能说明')).toBeTruthy()
    expect(mockPipeline.start).not.toHaveBeenCalled() // 未授权不录音
    fireEvent.click(screen.getByText('我知道了，同意使用'))
    expect(screen.queryByText('语音功能说明')).toBeNull()
    // 授权已写入 → 再按下直接录音
    fireEvent.pointerDown(bobo, { pointerId: 2, clientY: 200 })
    expect(mockPipeline.start).toHaveBeenCalledTimes(1)
  })

  it('语音授权弹窗：暂不使用关闭弹窗', () => {
    renderChatRoom()
    const bobo = screen.getAllByTestId('bobo-pet')[0]
    fireEvent.pointerDown(bobo, { pointerId: 1, clientY: 200 })
    expect(screen.getByText('语音功能说明')).toBeTruthy()
    fireEvent.click(screen.getByText('暂不使用'))
    expect(screen.queryByText('语音功能说明')).toBeNull()
  })

  it('语音唤醒授权弹窗：默认开启未授权 → 自动弹出，可开启', async () => {
    localStorage.removeItem('mindsafe_voicecall_consent_v1')
    renderChatRoom()
    await waitFor(() => expect(screen.getByText('语音唤醒说明')).toBeTruthy())
    fireEvent.click(screen.getByText('我知道了，开启'))
    await waitFor(() => expect(screen.queryByText('语音唤醒说明')).toBeNull())
  })

  it('语音唤醒授权弹窗：暂不使用关闭', async () => {
    localStorage.removeItem('mindsafe_voicecall_consent_v1')
    renderChatRoom()
    await waitFor(() => expect(screen.getByText('语音唤醒说明')).toBeTruthy())
    fireEvent.click(screen.getByText('暂不使用'))
    expect(screen.queryByText('语音唤醒说明')).toBeNull()
  })

  it('standby 模式下显示唤醒状态提示（loading/listening/error）', () => {
    mockVoiceCallState.mode = 'standby'
    mockVoiceCallState.wakeSupported = true
    mockVoiceCallState.wakeStatus = 'loading'
    const { rerenderChatRoom } = renderChatRoom()
    // F-29：未就绪时图标下醒目提示（琥珀胶囊文案）
    expect(screen.getByText('正在准备语音引擎…等会儿再叫我哦')).toBeTruthy()

    mockVoiceCallState.wakeStatus = 'listening'
    rerenderChatRoom()
    expect(screen.getByText('我在这里安静地等你叫我')).toBeTruthy()

    mockVoiceCallState.wakeStatus = 'error'
    rerenderChatRoom()
    expect(screen.getByText('语音引擎加载失败，请关闭再开启')).toBeTruthy()
  })

  it('analyzing 状态显示识别中提示', () => {
    mockPipeline.isAnalyzing = true
    renderChatRoom()
    expect(screen.getByText('正在识别，马上发送...')).toBeTruthy()
  })
})
