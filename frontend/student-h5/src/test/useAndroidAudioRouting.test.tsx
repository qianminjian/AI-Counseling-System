import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, fireEvent } from '@testing-library/react'
import { useAndroidAudioRouting } from '../hooks/useAndroidAudioRouting'

/**
 * useAndroidAudioRouting 接线 hook 行为测试（FA-17）
 * DC-012：播放中 → 释放麦克风流；未播放且（micWanted || 首次触摸）→ 600ms 后预热麦克风
 * 覆盖：600ms 时序 / pointerdown once 联动 / timer 清理（切播放/卸载）
 */

function setup(overrides: Partial<Parameters<typeof useAndroidAudioRouting>[0]> = {}) {
  const releaseStream = vi.fn()
  const warmUpMic = vi.fn()
  const props = { playing: false, micWanted: false, releaseStream, warmUpMic, ...overrides }
  const utils = renderHook((p) => useAndroidAudioRouting(p), { initialProps: props })
  return { ...utils, releaseStream, warmUpMic }
}

describe('useAndroidAudioRouting（FA-17）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('playing=true：立即释放麦克风流，不预热', () => {
    const { releaseStream, warmUpMic } = setup({ playing: true })
    expect(releaseStream).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(2000) })
    expect(warmUpMic).not.toHaveBeenCalled()
  })

  it('micWanted=true：600ms 后预热麦克风，之前不预热', () => {
    const { releaseStream, warmUpMic } = setup({ micWanted: true })
    act(() => { vi.advanceTimersByTime(599) })
    expect(warmUpMic).not.toHaveBeenCalled()
    act(() => { vi.advanceTimersByTime(1) })
    expect(warmUpMic).toHaveBeenCalledTimes(1)
    expect(releaseStream).not.toHaveBeenCalled()
  })

  it('无 micWanted 且未触摸：不预热；首次 pointerdown 后 600ms 预热', () => {
    const { warmUpMic } = setup()
    act(() => { vi.advanceTimersByTime(2000) })
    expect(warmUpMic).not.toHaveBeenCalled()

    act(() => { fireEvent.pointerDown(document) })
    act(() => { vi.advanceTimersByTime(600) })
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })

  it('pointerdown 为 once 监听：重复触摸只联动一次', () => {
    const { warmUpMic } = setup()
    act(() => {
      fireEvent.pointerDown(document)
      fireEvent.pointerDown(document)
    })
    act(() => { vi.advanceTimersByTime(600) })
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })

  it('timer 进行中切到 playing：预热被清理（不触发）', () => {
    const { warmUpMic, rerender } = setup({ micWanted: true })
    act(() => { vi.advanceTimersByTime(300) })
    rerender({ playing: true, micWanted: true, releaseStream: vi.fn(), warmUpMic })
    act(() => { vi.advanceTimersByTime(2000) })
    expect(warmUpMic).not.toHaveBeenCalled()
  })

  it('卸载前清理 timer：warmUpMic 不触发', () => {
    const { warmUpMic, unmount } = setup({ micWanted: true })
    unmount()
    act(() => { vi.advanceTimersByTime(2000) })
    expect(warmUpMic).not.toHaveBeenCalled()
  })

  it('触摸后保持 userInteracted：micWanted 关闭仍预热（用户已表达交互意图）', () => {
    const { warmUpMic, rerender } = setup()
    act(() => { fireEvent.pointerDown(document) })
    rerender({ playing: false, micWanted: false, releaseStream: vi.fn(), warmUpMic })
    act(() => { vi.advanceTimersByTime(600) })
    expect(warmUpMic).toHaveBeenCalledTimes(1)
  })
})
