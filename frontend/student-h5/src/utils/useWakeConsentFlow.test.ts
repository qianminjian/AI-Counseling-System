/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：唤醒授权联动 hook
 *
 * 语义（现 ChatRoom L110-125）：
 * - 挂载时 enabled 且未授权 → 800ms 后 requestConsent（合规弹窗，design/28 §1.4）
 * - 挂载时 enabled 且已授权 → onPreload（预加载唤醒模型，利用 TTS 播放时间窗口）
 * - enabled=false → 无动作
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWakeConsentFlow } from '../hooks/useWakeConsentFlow'

describe('useWakeConsentFlow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('已授权 → 立即 onPreload', () => {
    const onPreload = vi.fn()
    const requestConsent = vi.fn(() => true)
    renderHook(() => useWakeConsentFlow({ enabled: true, hasConsent: () => true, requestConsent, onPreload }))
    expect(onPreload).toHaveBeenCalledTimes(1)
    expect(requestConsent).not.toHaveBeenCalled()
  })

  it('未授权 → 800ms 后 requestConsent（提前不触发）', () => {
    const onPreload = vi.fn()
    const requestConsent = vi.fn(() => true)
    renderHook(() => useWakeConsentFlow({ enabled: true, hasConsent: () => false, requestConsent, onPreload }))
    expect(requestConsent).not.toHaveBeenCalled()
    expect(onPreload).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(799))
    expect(requestConsent).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(requestConsent).toHaveBeenCalledTimes(1)
  })

  it('enabled=false → 无动作（不弹窗不预加载）', () => {
    const onPreload = vi.fn()
    const requestConsent = vi.fn(() => true)
    renderHook(() => useWakeConsentFlow({ enabled: false, hasConsent: () => false, requestConsent, onPreload }))
    act(() => vi.advanceTimersByTime(2000))
    expect(requestConsent).not.toHaveBeenCalled()
    expect(onPreload).not.toHaveBeenCalled()
  })
})
