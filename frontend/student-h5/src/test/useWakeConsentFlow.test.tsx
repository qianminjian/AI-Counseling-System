import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWakeConsentFlow } from '../hooks/useWakeConsentFlow'

/**
 * useWakeConsentFlow 接线 hook 行为测试（FA-17）
 * DC-012（SPEC §26）：enabled 且未授权 → 800ms 后弹授权；已授权 → 立即预加载
 * 覆盖：800ms 时序 / 授权分支 / timer 清理（卸载前不弹）
 */

describe('useWakeConsentFlow（FA-17）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('enabled=false：不弹授权也不预加载，推进时间仍无动作', () => {
    const requestConsent = vi.fn(() => true)
    const onPreload = vi.fn()
    renderHook(() => useWakeConsentFlow({ enabled: false, hasConsent: () => false, requestConsent, onPreload }))

    act(() => { vi.advanceTimersByTime(2000) })
    expect(requestConsent).not.toHaveBeenCalled()
    expect(onPreload).not.toHaveBeenCalled()
  })

  it('enabled=true 且未授权：800ms 后才弹授权，之前不弹', () => {
    const requestConsent = vi.fn(() => true)
    const onPreload = vi.fn()
    renderHook(() => useWakeConsentFlow({ enabled: true, hasConsent: () => false, requestConsent, onPreload }))

    act(() => { vi.advanceTimersByTime(799) })
    expect(requestConsent).not.toHaveBeenCalled()

    act(() => { vi.advanceTimersByTime(1) })
    expect(requestConsent).toHaveBeenCalledTimes(1)
    expect(onPreload).not.toHaveBeenCalled()
  })

  it('enabled=true 且已授权：立即预加载，不弹授权', () => {
    const requestConsent = vi.fn(() => true)
    const onPreload = vi.fn()
    renderHook(() => useWakeConsentFlow({ enabled: true, hasConsent: () => true, requestConsent, onPreload }))

    expect(onPreload).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(2000) })
    expect(requestConsent).not.toHaveBeenCalled()
  })

  it('800ms 前卸载：授权弹窗被清理，不触发', () => {
    const requestConsent = vi.fn(() => true)
    const onPreload = vi.fn()
    const { unmount } = renderHook(() => useWakeConsentFlow({ enabled: true, hasConsent: () => false, requestConsent, onPreload }))

    unmount()
    act(() => { vi.advanceTimersByTime(2000) })
    expect(requestConsent).not.toHaveBeenCalled()
  })
})
