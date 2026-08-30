import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useIdleLogout } from '../hooks/useIdleLogout'

describe('hooks/useIdleLogout', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('enabled=false 时不触发警告', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: false,
      idleMs: 1000,
      countdownSec: 5,
      onTimeout,
    }))
    act(() => { vi.advanceTimersByTime(10000) })
    expect(result.current.warning).toBe(false)
    expect(onTimeout).not.toHaveBeenCalled()
  })

  it('空闲超过 idleMs 后显示警告', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 5000,
      countdownSec: 10,
      onTimeout,
    }))
    expect(result.current.warning).toBe(false)
    act(() => { vi.advanceTimersByTime(5001) })
    expect(result.current.warning).toBe(true)
    expect(result.current.secondsLeft).toBe(10)
  })

  it('倒计时归零触发 onTimeout', () => {
    const onTimeout = vi.fn()
    renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 1000,
      countdownSec: 3,
      onTimeout,
    }))
    // 触发警告
    act(() => { vi.advanceTimersByTime(1001) })
    // 倒计时 3 秒
    act(() => { vi.advanceTimersByTime(3000) })
    expect(onTimeout).toHaveBeenCalledTimes(1)
  })

  it('stay() 关闭警告并重置', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 1000,
      countdownSec: 60,
      onTimeout,
    }))
    act(() => { vi.advanceTimersByTime(1001) })
    expect(result.current.warning).toBe(true)

    act(() => { result.current.stay() })
    expect(result.current.warning).toBe(false)
    expect(result.current.secondsLeft).toBe(60)
    expect(onTimeout).not.toHaveBeenCalled()
  })

  it('警告期间普通触摸不重置计时', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 1000,
      countdownSec: 5,
      onTimeout,
    }))
    // 触发警告
    act(() => { vi.advanceTimersByTime(1001) })
    expect(result.current.warning).toBe(true)

    // 模拟触摸事件（警告期间应被忽略）
    act(() => { window.dispatchEvent(new Event('pointerdown')) })
    // 继续倒计时
    act(() => { vi.advanceTimersByTime(5000) })
    expect(onTimeout).toHaveBeenCalledTimes(1)
  })

  it('活动事件重置空闲计时（警告前）', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 5000,
      countdownSec: 5,
      onTimeout,
    }))
    // 3秒后触摸
    act(() => { vi.advanceTimersByTime(3000) })
    act(() => { window.dispatchEvent(new Event('pointerdown')) })
    // 再过 3 秒（总共 6 秒，但距上次活动只有 3 秒）
    act(() => { vi.advanceTimersByTime(3000) })
    expect(result.current.warning).toBe(false)
    expect(onTimeout).not.toHaveBeenCalled()
  })

  it('secondsLeft 逐秒递减', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 1000,
      countdownSec: 10,
      onTimeout,
    }))
    act(() => { vi.advanceTimersByTime(1001) })
    expect(result.current.secondsLeft).toBe(10)
    act(() => { vi.advanceTimersByTime(2000) })
    expect(result.current.secondsLeft).toBeLessThanOrEqual(8)
  })

  it('markActivity 重置空闲计时（语音交互信号，警告前生效）', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 5000,
      countdownSec: 5,
      onTimeout,
    }))
    // 3 秒后语音交互（AI 回复完成/唤醒命中）
    act(() => { vi.advanceTimersByTime(3000) })
    act(() => { result.current.markActivity() })
    // 再过 3 秒（距上次活动仅 3 秒）→ 不触发警告
    act(() => { vi.advanceTimersByTime(3000) })
    expect(result.current.warning).toBe(false)
    expect(onTimeout).not.toHaveBeenCalled()
  })

  it('markActivity 警告期间不解除（语音交互不延长倒计时，只认「我还在」）', () => {
    const onTimeout = vi.fn()
    const { result } = renderHook(() => useIdleLogout({
      enabled: true,
      idleMs: 1000,
      countdownSec: 5,
      onTimeout,
    }))
    act(() => { vi.advanceTimersByTime(1001) })
    expect(result.current.warning).toBe(true)

    act(() => { result.current.markActivity() })
    // 倒计时不被语音活动打断
    act(() => { vi.advanceTimersByTime(5000) })
    expect(onTimeout).toHaveBeenCalledTimes(1)
  })
})
