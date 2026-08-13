import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

// ===== mock api 与 emotionBus =====
const mockAuthFetch = vi.fn()
const mockApi = vi.fn()
const mocks = vi.hoisted(() => ({ publish: vi.fn() }))
vi.mock('../api', () => ({
  authFetch: (...args: any[]) => mockAuthFetch(...args),
  api: (...args: any[]) => mockApi(...args),
  getUser: () => ({ gender: 'male', pseudonym: '小明' }),
}))
vi.mock('../utils/emotionBus', () => ({
  emotionBus: { publish: mocks.publish },
}))

import { useChatSession } from '../hooks/useChatSession'

const encoder = new TextEncoder()

function sseResponse(chunks: Uint8Array[]) {
  let readIdx = 0
  return {
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
  }
}

function makeTts() {
  return {
    muted: false,
    stop: vi.fn(),
    unlock: vi.fn(),
    startStreaming: vi.fn(),
    feedToken: vi.fn(),
    endStreaming: vi.fn(),
  }
}

const SESSION = { sessionId: 'sess-1', greeting: '你好呀！', emotionTag: 'neutral' }

describe('useChatSession（UX-006，design/17 §chat/hooks）', () => {
  let tts: ReturnType<typeof makeTts>
  let bobo: { dispatch: ReturnType<typeof vi.fn> }
  let onInteraction: ReturnType<typeof vi.fn>
  let onClosed: ReturnType<typeof vi.fn>
  let opts: any

  beforeEach(() => {
    vi.clearAllMocks()
    tts = makeTts()
    bobo = { dispatch: vi.fn() }
    onInteraction = vi.fn()
    onClosed = vi.fn()
    opts = {
      sessionId: SESSION.sessionId,
      greeting: SESSION.greeting,
      emotionTag: SESSION.emotionTag,
      tts,
      wakeEnabled: false,
      bobo,
      onInteraction,
      onClosed,
    }
  })

  it('初始消息：问候语 assistant 气泡', () => {
    const { result } = renderHook(() => useChatSession(opts))
    expect(result.current.messages).toEqual([
      { role: 'assistant', content: '你好呀！', emotion: 'neutral' },
    ])
  })

  it('sendMessage：追加 user 消息 + 占位 → 流式 token 更新回复', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([
      encoder.encode('data:{"type":"token","content":"你好"}\n\n'),
      encoder.encode('data:{"type":"token","content":"呀"}\n\n'),
    ]))
    const { result } = renderHook(() => useChatSession(opts))

    let sent = false
    await act(async () => {
      sent = await result.current.sendMessage('我今天很开心')
    })

    expect(sent).toBe(true)
    expect(result.current.messages).toEqual([
      { role: 'assistant', content: '你好呀！', emotion: 'neutral' },
      { role: 'user', content: '我今天很开心', emotion: undefined },
      { role: 'assistant', content: '你好呀', emotion: 'neutral' },
    ])
    expect(onInteraction).toHaveBeenCalled()
    expect(tts.startStreaming).toHaveBeenCalled()
    expect(tts.feedToken).toHaveBeenCalledTimes(2)
    expect(tts.endStreaming).toHaveBeenCalled()
    expect(result.current.streaming).toBe(false)
  })

  it('语音自动发送：body 携带 voiceEmotion 字段', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([]))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('我很开心', { label: '开心', labelEn: 'happy', confidence: 0.9, scores: [] })
    })

    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/chat/sessions/sess-1/messages',
      expect.objectContaining({
        body: expect.stringContaining('"voiceEmotion":"happy"'),
      }),
    )
    // 用户消息气泡挂上情绪
    expect(result.current.messages[1].emotion).toBe('happy')
  })

  it('空文本或 streaming 中 → 返回 false 不请求', async () => {
    const { result } = renderHook(() => useChatSession(opts))

    let sent = await result.current.sendMessage('   ')
    expect(sent).toBe(false)
    expect(mockAuthFetch).not.toHaveBeenCalled()
    expect(sent).toBe(false)
  })

  it('onBeforeSend：发送前回调先执行（打断朗读/释放麦克风）', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([]))
    const beforeSend = vi.fn()
    opts.onBeforeSend = beforeSend
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('测试')
    })

    expect(beforeSend).toHaveBeenCalled()
  })

  it('发送失败 → 错误气泡替换占位', async () => {
    mockAuthFetch.mockRejectedValue(new Error('network'))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('测试')
    })

    expect(result.current.messages[2]).toEqual({
      role: 'assistant', content: '网络出了点问题，请再试一次哦 🙏', emotion: null,
    })
    // 无回复 → 不 endStreaming，走 stop
    expect(tts.endStreaming).not.toHaveBeenCalled()
    expect(tts.stop).toHaveBeenCalled()
  })

  it('流异常但已收到部分回复 → 保留内容不报错', async () => {
    let readIdx = 0
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readIdx === 0) {
              readIdx++
              return Promise.resolve({ done: false, value: encoder.encode('data:{"type":"token","content":"你好"}\n\n') })
            }
            return Promise.reject(new Error('stream broken'))
          },
          cancel: vi.fn(),
        }),
      },
    })
    const { result } = renderHook(() => useChatSession(opts))

    let sent = false
    await act(async () => {
      sent = await result.current.sendMessage('测试')
    })

    expect(sent).toBe(true)
    expect(result.current.messages[2].content).toBe('你好')
    expect(result.current.messages[2].content).not.toContain('网络出了点问题')
  })

  it('emotion 事件：emotionBus.publish + 消息 emotion 同源 + 波波保持情绪表情（不回落 idle）', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([
      encoder.encode('data:{"type":"emotion","content":"happy"}\n\n'),
      encoder.encode('data:{"type":"token","content":"没事的"}\n\n'),
    ]))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('测试')
    })

    expect(mocks.publish).toHaveBeenCalledWith('happy')
    // FA-09：气泡与总线同源——消息 emotion 与总线同一标签（design/37 §三.1 三方同源）
    expect(result.current.messages[2].emotion).toBe('happy')
    expect(bobo.dispatch).not.toHaveBeenCalledWith({ type: 'idle' })
  })

  it('risk 事件：波波 risk 姿态，不渲染到消息', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([
      encoder.encode('data:{"type":"risk","content":"观察","metadata":{"riskLevel":2}}\n\n'),
      encoder.encode('data:{"type":"token","content":"老师会帮你的"}\n\n'),
    ]))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('我很难过')
    })

    expect(bobo.dispatch).toHaveBeenCalledWith({ type: 'risk', riskLevel: 2 })
    expect(result.current.messages[2].content).toBe('老师会帮你的')
  })

  it('无 emotion 事件 → 回复结束后波波回落 idle', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([
      encoder.encode('data:{"type":"token","content":"好的"}\n\n'),
    ]))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.sendMessage('测试')
    })

    expect(bobo.dispatch).toHaveBeenCalledWith({ type: 'idle' })
  })

  it('deduplicateText：检测后半段重复（Android 语音识别 bug 防护）', () => {
    const { result } = renderHook(() => useChatSession(opts))
    expect(result.current.deduplicateText('我想说的是我想说的是')).toBe('我想说的是')
    expect(result.current.deduplicateText('你好')).toBe('你好')
  })

  it('sendMessageRef 与最新 sendMessage 同步（供录音完成回调调用）', async () => {
    mockAuthFetch.mockResolvedValue(sseResponse([]))
    const { result } = renderHook(() => useChatSession(opts))

    // 每次渲染后 ref 指向最新实现
    await act(async () => {
      result.current.sendMessageRef.current?.('录音转写文本', null)
    })

    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/chat/sessions/sess-1/messages',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('closeSession：主接口成功 → onClosed 回调', async () => {
    mockApi.mockResolvedValue({})
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.closeSession(5, '很好')
    })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/sessions/sess-1/close',
      expect.objectContaining({ method: 'POST', body: expect.stringContaining('"rating":5') }),
    )
    expect(onClosed).toHaveBeenCalled()
  })

  it('closeSession：主接口失败 → 不回退旧接口，onClosed 仍触发（ARCH-010 D5）', async () => {
    mockApi.mockRejectedValue(new Error('not found'))
    const { result } = renderHook(() => useChatSession(opts))

    await act(async () => {
      await result.current.closeSession(null)
    })

    expect(mockApi).toHaveBeenCalledTimes(1)
    expect(mockApi).toHaveBeenCalledWith('/api/v1/sessions/sess-1/close', expect.objectContaining({ method: 'POST' }))
    expect(onClosed).toHaveBeenCalled()
  })
})
