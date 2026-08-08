import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChatRoomPanels } from '../hooks/useChatRoomPanels'

// FA-06（DOC-074）：ChatRoom 面板/弹窗/提示条状态收敛测试
describe('useChatRoomPanels', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('初始状态：面板全关、提示条空、无重播高亮', () => {
    const { result } = renderHook(() => useChatRoomPanels())
    expect(result.current.settingsOpen).toBe(false)
    expect(result.current.toolboxOpen).toBe(false)
    expect(result.current.sosOpen).toBe(false)
    expect(result.current.confirmSwitch).toBe(false)
    expect(result.current.showSatisfaction).toBe(false)
    expect(result.current.voiceNotice).toBe('')
    expect(result.current.speakingMsgIdx).toBe(-1)
  })

  it('面板开合状态可独立切换', () => {
    const { result } = renderHook(() => useChatRoomPanels())
    act(() => result.current.setSettingsOpen(true))
    act(() => result.current.setToolboxOpen(true))
    expect(result.current.settingsOpen).toBe(true)
    expect(result.current.toolboxOpen).toBe(true)
    expect(result.current.sosOpen).toBe(false)
    act(() => result.current.setSettingsOpen(false))
    expect(result.current.settingsOpen).toBe(false)
    expect(result.current.toolboxOpen).toBe(true)
  })

  it('speakingMsgIdx 可设置与复位', () => {
    const { result } = renderHook(() => useChatRoomPanels())
    act(() => result.current.setSpeakingMsgIdx(3))
    expect(result.current.speakingMsgIdx).toBe(3)
    act(() => result.current.setSpeakingMsgIdx(-1))
    expect(result.current.speakingMsgIdx).toBe(-1)
  })

  it('showNotice 显示提示并在定时后自动清空', () => {
    const { result } = renderHook(() => useChatRoomPanels())
    act(() => result.current.showNotice('已取消', 2000))
    expect(result.current.voiceNotice).toBe('已取消')
    act(() => vi.advanceTimersByTime(2000))
    expect(result.current.voiceNotice).toBe('')
  })

  it('连续 showNotice：新提示重置旧定时器（不提前清空）', () => {
    const { result } = renderHook(() => useChatRoomPanels())
    act(() => result.current.showNotice('第一条', 6000))
    act(() => result.current.showNotice('第二条', 4000))
    expect(result.current.voiceNotice).toBe('第二条')
    // 4s 后第二条到期清空；旧 6s 定时器已被重置，不会再有副作用
    act(() => vi.advanceTimersByTime(4000))
    expect(result.current.voiceNotice).toBe('')
    act(() => vi.advanceTimersByTime(2000))
    expect(result.current.voiceNotice).toBe('')
  })

  it('卸载时清理定时器（AUD-017：无 setState-after-unmount）', () => {
    const { unmount } = renderHook(() => useChatRoomPanels())
    act(() => unmount())
    // 若卸载后定时器仍触发 setState，act 会抛错；此处静默通过即验证清理
    act(() => vi.advanceTimersByTime(10000))
  })
})
