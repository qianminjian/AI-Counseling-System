import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useSilenceNudge } from '../hooks/useSilenceNudge'

// P0-1：hook 走统一认证接缝 fetchWarmPrompt（api.ts），不再裸 fetch
const { mockFetchWarmPrompt } = vi.hoisted(() => ({
  mockFetchWarmPrompt: vi.fn(),
}))
vi.mock('../api', () => ({
  fetchWarmPrompt: mockFetchWarmPrompt,
}))

describe('useSilenceNudge', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockFetchWarmPrompt.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('返回 recordInteraction 和 resetSilenceBase 函数', () => {
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge: vi.fn() })
    )
    expect(typeof result.current.recordInteraction).toBe('function')
    expect(typeof result.current.resetSilenceBase).toBe('function')
  })

  it('idle=false 时不启动定时器', () => {
    renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: false, onNudge: vi.fn() })
    )
    act(() => { vi.advanceTimersByTime(60000) })
    expect(mockFetchWarmPrompt).not.toHaveBeenCalled()
  })

  it('未互动时（interactedRef=false）不触发 nudge', async () => {
    renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge: vi.fn() })
    )
    // 超过 25s 沉默 + 多个检测周期
    act(() => { vi.advanceTimersByTime(35000) })
    expect(mockFetchWarmPrompt).not.toHaveBeenCalled()
  })

  it('recordInteraction 后沉默 >=25s 触发暖场请求（走 authFetch 接缝）', async () => {
    const mockReader = {
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode('data:{"type":"token","content":"你好呀"}\n'),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
    }
    mockFetchWarmPrompt.mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )

    // 模拟孩子说话
    act(() => { result.current.recordInteraction() })

    // 推进 25s+ 沉默（检测间隔 5s）
    await act(async () => { vi.advanceTimersByTime(30000) })

    expect(mockFetchWarmPrompt).toHaveBeenCalledWith('s1', expect.any(Number))
    expect(onNudge).toHaveBeenCalledWith('你好呀')
  })

  it('空流（后端留白）不触发 onNudge', async () => {
    const mockReader = {
      read: vi.fn().mockResolvedValueOnce({ done: true, value: undefined }),
    }
    mockFetchWarmPrompt.mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )
    act(() => { result.current.recordInteraction() })
    await act(async () => { vi.advanceTimersByTime(30000) })

    expect(mockFetchWarmPrompt).toHaveBeenCalled()
    expect(onNudge).not.toHaveBeenCalled()
  })

  it('连续暖场上限 2 次', async () => {
    const mockReader = () => ({
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode('data:{"type":"token","content":"暖场"}\n'),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
    })
    mockFetchWarmPrompt.mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader() },
    })

    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )
    act(() => { result.current.recordInteraction() })

    // 第 1 次 nudge
    await act(async () => { vi.advanceTimersByTime(30000) })
    // 第 2 次 nudge（需等 20s 间隔）
    await act(async () => { vi.advanceTimersByTime(30000) })
    // 第 3 次应被阻止（连续上限 2）
    await act(async () => { vi.advanceTimersByTime(30000) })

    expect(onNudge).toHaveBeenCalledTimes(2)
  })

  it('recordInteraction 清零连续暖场计数', async () => {
    const mockReader = () => ({
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode('data:{"type":"token","content":"hi"}\n'),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
    })
    mockFetchWarmPrompt.mockResolvedValue({
      ok: true,
      body: { getReader: () => mockReader() },
    })

    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )
    act(() => { result.current.recordInteraction() })
    await act(async () => { vi.advanceTimersByTime(30000) })
    await act(async () => { vi.advanceTimersByTime(30000) })
    expect(onNudge).toHaveBeenCalledTimes(2)

    // 孩子再次说话 → 清零
    act(() => { result.current.recordInteraction() })
    await act(async () => { vi.advanceTimersByTime(30000) })
    expect(onNudge).toHaveBeenCalledTimes(3)
  })

  it('暖场请求失败静默忽略（不打断对话）', async () => {
    mockFetchWarmPrompt.mockRejectedValue(new Error('network'))
    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )
    act(() => { result.current.recordInteraction() })
    await act(async () => { vi.advanceTimersByTime(30000) })
    expect(onNudge).not.toHaveBeenCalled()
  })

  it('HTTP 非 2xx 静默忽略', async () => {
    mockFetchWarmPrompt.mockResolvedValue({ ok: false, status: 500 })
    const onNudge = vi.fn()
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge })
    )
    act(() => { result.current.recordInteraction() })
    await act(async () => { vi.advanceTimersByTime(30000) })
    expect(onNudge).not.toHaveBeenCalled()
  })

  it('resetSilenceBase 重置沉默起算时间', async () => {
    const { result } = renderHook(() =>
      useSilenceNudge({ sessionId: 's1', idle: true, onNudge: vi.fn() })
    )
    act(() => { result.current.recordInteraction() })
    // 推进 20s 后重置
    act(() => { vi.advanceTimersByTime(20000) })
    act(() => { result.current.resetSilenceBase() })
    // 再推进 10s（总 30s 但重置后只有 10s < 25s）
    act(() => { vi.advanceTimersByTime(10000) })
    expect(mockFetchWarmPrompt).not.toHaveBeenCalled()
  })
})
