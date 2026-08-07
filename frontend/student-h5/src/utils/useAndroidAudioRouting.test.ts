/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：安卓音频路由保护 hook
 *
 * 语义（现 ChatRoom L187-209）：
 * - 首次 pointerdown 记录 userInteracted（once 监听）
 * - 播放中 → releaseStream（安卓路由保护：避免 TTS 被路由到听筒）
 * - 未播放且（micWanted || userInteracted）→ 600ms 后 warmUpMic（下次录音秒开）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAndroidAudioRouting } from '../hooks/useAndroidAudioRouting'

describe('useAndroidAudioRouting', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('播放中 → 立即 releaseStream', () => {
    const releaseStream = vi.fn()
    const warmUpMic = vi.fn()
    renderHook(() => useAndroidAudioRouting({ playing: true, micWanted: false, releaseStream, warmUpMic }))
    expect(releaseStream).toHaveBeenCalledTimes(1)
    expect(warmUpMic).not.toHaveBeenCalled()
  })

  it('micWanted 且未播放 → 600ms 后 warmUpMic（提前不触发）', () => {
    const releaseStream = vi.fn()
    const warmUpMic = vi.fn()
    renderHook(() => useAndroidAudioRouting({ playing: false, micWanted: true, releaseStream, warmUpMic }))
    expect(warmUpMic).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(599))
    expect(warmUpMic).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })

  it('未交互且 micWanted=false → 预热挂起（不调度）', () => {
    const warmUpMic = vi.fn()
    renderHook(() => useAndroidAudioRouting({ playing: false, micWanted: false, releaseStream: vi.fn(), warmUpMic }))
    act(() => vi.advanceTimersByTime(2000))
    expect(warmUpMic).not.toHaveBeenCalled()
  })

  it('首次 pointerdown → 解除挂起，600ms 后 warmUpMic', () => {
    const warmUpMic = vi.fn()
    renderHook(() => useAndroidAudioRouting({ playing: false, micWanted: false, releaseStream: vi.fn(), warmUpMic }))
    act(() => {
      document.dispatchEvent(new Event('pointerdown'))
    })
    act(() => vi.advanceTimersByTime(600))
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })

  it('playing 变为 false 且 micWanted 满足 → 重新调度 600ms 预热', () => {
    const releaseStream = vi.fn()
    const warmUpMic = vi.fn()
    const { rerender } = renderHook(
      ({ playing }) => useAndroidAudioRouting({ playing, micWanted: true, releaseStream, warmUpMic }),
      { initialProps: { playing: true } }
    )
    expect(releaseStream).toHaveBeenCalledTimes(1)
    rerender({ playing: false })
    act(() => vi.advanceTimersByTime(600))
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })
})
