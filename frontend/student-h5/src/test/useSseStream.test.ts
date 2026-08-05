import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useSseStream } from '../hooks/useSseStream'

// ===== mock api（authFetch 是 SSE 唯一网络入口）=====
const mockAuthFetch = vi.fn()
vi.mock('../api', () => ({
  authFetch: (...args: any[]) => mockAuthFetch(...args),
  api: vi.fn(),
  getUser: () => ({ gender: 'male', pseudonym: '小明' }),
}))

const encoder = new TextEncoder()

/** 构造 SSE chunk 流 reader（与 ChatRoom.test.tsx 相同模式） */
function sseReader(chunks: Uint8Array[], onRead?: (signal: AbortSignal | undefined) => void) {
  let readIdx = 0
  return {
    getReader: () => ({
      read: () => {
        if (onRead) onRead(undefined)
        if (readIdx < chunks.length) return Promise.resolve({ done: false, value: chunks[readIdx++] })
        return Promise.resolve({ done: true, value: undefined })
      },
      cancel: vi.fn(),
    }),
  }
}

describe('useSseStream（UX-006，design/17 §chat/hooks）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('token 事件逐条回调 onToken，fullResponse 累加', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: sseReader([
        encoder.encode('data:{"type":"token","content":"你好"}\n\n'),
        encoder.encode('data:{"type":"token","content":"呀"}\n\n'),
      ]),
    })
    const { result } = renderHook(() => useSseStream())
    const onToken = vi.fn()

    let outcome: any
    await act(async () => {
      outcome = await result.current.streamMessage('/api/v1/chat/messages', { content: 'hi' }, { onToken, onEmotion: vi.fn(), onRisk: vi.fn() })
    })

    expect(onToken).toHaveBeenCalledTimes(2)
    expect(onToken).toHaveBeenNthCalledWith(1, '你好')
    expect(onToken).toHaveBeenNthCalledWith(2, '呀')
    expect(outcome.fullResponse).toBe('你好呀')
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/chat/messages',
      expect.objectContaining({ method: 'POST', headers: { 'Content-Type': 'application/json' } }),
    )
  })

  it('emotion 事件回调 onEmotion', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: sseReader([
        encoder.encode('data:{"type":"emotion","content":"happy"}\n\n'),
        encoder.encode('data:{"type":"token","content":"没事的"}\n\n'),
      ]),
    })
    const { result } = renderHook(() => useSseStream())
    const onEmotion = vi.fn()

    await act(async () => {
      await result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion, onRisk: vi.fn() })
    })

    expect(onEmotion).toHaveBeenCalledWith('happy')
  })

  it('risk 事件回调 onRisk（riskLevel 数字 + content）', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: sseReader([
        encoder.encode('data:{"type":"risk","content":"观察","metadata":{"riskLevel":2}}\n\n'),
      ]),
    })
    const { result } = renderHook(() => useSseStream())
    const onRisk = vi.fn()

    await act(async () => {
      await result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion: vi.fn(), onRisk })
    })

    expect(onRisk).toHaveBeenCalledWith(2, '观察')
  })

  it('非 data: 行与坏 JSON 静默忽略', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: sseReader([
        encoder.encode('event: foo\ndata:{"type":"token","content":"A"}\n\n'),
        encoder.encode('data:not-json\n\n'),
        encoder.encode('data:{"type":"token","content":"B"}\n\n'),
      ]),
    })
    const { result } = renderHook(() => useSseStream())
    const onToken = vi.fn()

    let outcome: any
    await act(async () => {
      outcome = await result.current.streamMessage('/u', {}, { onToken, onEmotion: vi.fn(), onRisk: vi.fn() })
    })

    expect(onToken).toHaveBeenCalledTimes(2)
    expect(outcome.fullResponse).toBe('AB')
  })

  it('跨 chunk 半行缓冲：split 边界 token 正确拼接', async () => {
    // 将一条 SSE 事件切成两个 chunk，验证 buffer 拼接逻辑
    const full = encoder.encode('data:{"type":"token","content":"跨块"}\n\n')
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: sseReader([full.slice(0, 12), full.slice(12)]),
    })
    const { result } = renderHook(() => useSseStream())
    const onToken = vi.fn()

    let outcome: any
    await act(async () => {
      outcome = await result.current.streamMessage('/u', {}, { onToken, onEmotion: vi.fn(), onRisk: vi.fn() })
    })

    expect(onToken).toHaveBeenCalledWith('跨块')
    expect(outcome.fullResponse).toBe('跨块')
  })

  it('HTTP 非 ok → reject', async () => {
    mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
    const { result } = renderHook(() => useSseStream())

    await expect(
      result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion: vi.fn(), onRisk: vi.fn() }),
    ).rejects.toThrow('HTTP 500')
  })

  it('流中途异常且无回复 → reject（由上层显示错误气泡）', async () => {
    let readIdx = 0
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => {
            if (readIdx === 0) { readIdx++; return Promise.resolve({ done: false, value: encoder.encode('data:{"type":"token","content":"A"}\n\n') }) }
            return Promise.reject(new Error('stream broken'))
          },
          cancel: vi.fn(),
        }),
      },
    })
    const { result } = renderHook(() => useSseStream())
    const onToken = vi.fn()

    await expect(
      result.current.streamMessage('/u', {}, { onToken, onEmotion: vi.fn(), onRisk: vi.fn() }),
    ).rejects.toThrow('stream broken')
    // 已收到的 token 已回调（上层自行决定保留/丢弃）
    expect(onToken).toHaveBeenCalledWith('A')
  })

  it('streaming 状态：流式期间 true，结束后 false', async () => {
    let resolveRead: any
    mockAuthFetch.mockResolvedValue({
      ok: true,
      body: {
        getReader: () => ({
          read: () => new Promise((r) => { resolveRead = r }),
          cancel: vi.fn(),
        }),
      },
    })
    const { result } = renderHook(() => useSseStream())

    let pending: Promise<any>
    // async act 冲刷微任务，确保 read() 已调用、resolveRead 已赋值
    await act(async () => {
      pending = result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion: vi.fn(), onRisk: vi.fn() })
    })
    expect(result.current.streaming).toBe(true)

    await act(async () => {
      resolveRead({ done: true, value: undefined })
      await pending
    })
    expect(result.current.streaming).toBe(false)
  })

  it('30s 无数据超时 → abort 中断流（防永远卡在 streaming 态）', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    mockAuthFetch.mockImplementation((_url: string, init?: any) => {
      signal = init?.signal
      return Promise.resolve({
        ok: true,
        body: {
          getReader: () => ({
            read: () => new Promise((_r, reject) => {
              signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
            }),
            cancel: vi.fn(),
          }),
        },
      })
    })
    const { result } = renderHook(() => useSseStream())

    const promise = result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion: vi.fn(), onRisk: vi.fn() })
    // 30s 超时判定为 >30000ms，interval 每 5s tick → 第 7 次 tick（35s）触发 abort
    await act(async () => { await vi.advanceTimersByTimeAsync(40000) })

    await expect(promise).rejects.toThrow()
    expect(result.current.streaming).toBe(false)
  })

  it('stopStream 主动中断：挂起的流被 abort', async () => {
    let signal: AbortSignal | undefined
    mockAuthFetch.mockImplementation((_url: string, init?: any) => {
      signal = init?.signal
      return Promise.resolve({
        ok: true,
        body: {
          getReader: () => ({
            read: () => new Promise((_r, reject) => {
              signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
            }),
            cancel: vi.fn(),
          }),
        },
      })
    })
    const { result } = renderHook(() => useSseStream())

    let promise: Promise<any>
    await act(async () => {
      promise = result.current.streamMessage('/u', {}, { onToken: vi.fn(), onEmotion: vi.fn(), onRisk: vi.fn() })
    })
    await act(async () => {
      result.current.stopStream()
    })

    await expect(promise).rejects.toThrow()
  })
})
