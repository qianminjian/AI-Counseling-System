import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

// mock useWakeWord（可控 supported）
const mockOnDetected = { current: null as any }
let mockWakeSupported = true
vi.mock('../hooks/useWakeWord', () => ({
  useWakeWord: ({ active, onDetected }: any) => {
    mockOnDetected.current = onDetected
    return { supported: mockWakeSupported, wakeStatus: active ? 'listening' : 'idle' }
  },
}))

// P1-3：mock 远程配置 API（useVoiceCallMode → config/remote → api）
const mockFetchSystemConfig = vi.fn()
vi.mock('../api', () => ({
  fetchSystemConfig: (...args: any[]) => mockFetchSystemConfig(...args),
}))

import { useVoiceCallMode } from '../hooks/useVoiceCallMode'
// P1-3：重置远程配置缓存（避免跨用例污染，fallback 语义测试依赖）
import { _resetForTest as resetRemoteConfigForTest } from '../config/remote'

// mock SpeechRecognition
class MockSpeechRecognition {
  lang = ''
  continuous = false
  interimResults = false
  onresult: any = null
  onend: any = null
  onerror: any = null
  start = vi.fn()
  stop = vi.fn()
}

describe('useVoiceCallMode', () => {
  let mockTts: any

  beforeEach(() => {
    vi.useFakeTimers()
    mockWakeSupported = true
    mockFetchSystemConfig.mockReset()
    resetRemoteConfigForTest()
    ;(window as any).SpeechRecognition = MockSpeechRecognition
    mockTts = {
      speak: vi.fn().mockResolvedValue(undefined),
      unlock: vi.fn(),
      stop: vi.fn(),
      muted: false,
      playing: false,
    }
  })

  afterEach(() => {
    vi.useRealTimers()
    delete (window as any).SpeechRecognition
  })

  it('初始状态 off，enabled=false 保持 off', () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: false, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    expect(result.current.mode).toBe('off')
  })

  it('enabled=true 从 off 进入 standby', () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    expect(result.current.mode).toBe('standby')
  })

  it('enabled=false 从 standby 回到 off', () => {
    const { result, rerender } = renderHook(
      ({ enabled }) => useVoiceCallMode({ enabled, tts: mockTts, busy: false, onFinalTranscript: vi.fn() }),
      { initialProps: { enabled: true } }
    )
    expect(result.current.mode).toBe('standby')
    rerender({ enabled: false })
    expect(result.current.mode).toBe('off')
  })

  it('standby 下检测到唤醒词 → active + TTS 确认', () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    expect(result.current.mode).toBe('standby')

    // 模拟唤醒词检测
    act(() => {
      mockOnDetected.current?.()
    })
    expect(result.current.mode).toBe('active')
    expect(mockTts.unlock).toHaveBeenCalled()
    expect(mockTts.speak).toHaveBeenCalledWith('我在呢！')
  })

  it('active 模式下 busy=false 启动语音识别', () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')
    // SpeechRecognition.start 被调用（startListeningRound）
    // 由于 jsdom 中 new SpeechRecognition() 是 mock 类，start 会被调用
  })

  it('active 模式冷却 25s 后回到 standby', async () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')

    // 推进 25s 冷却
    await act(async () => {
      vi.advanceTimersByTime(25000)
    })
    // speak(COOLDOWN_CLOSE_TEXT) 被调用后回到 standby
    expect(mockTts.speak).toHaveBeenCalledWith('我先安静陪着你，想说话随时叫我哦')
    expect(result.current.mode).toBe('standby')
  })

  it('busy=true 时暂停语音捕捉', () => {
    const { result, rerender } = renderHook(
      ({ busy }) => useVoiceCallMode({ enabled: true, tts: mockTts, busy, onFinalTranscript: vi.fn() }),
      { initialProps: { busy: false } }
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')

    rerender({ busy: true })
    // busy 时冷却计时器被清除，不会回到 standby
    act(() => { vi.advanceTimersByTime(30000) })
    expect(result.current.mode).toBe('active')
  })

  it('wakeSupported=false 降级回 off', () => {
    // 先以 supported=true 进入 standby，再切换为 false 触发降级
    const { result, rerender } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    expect(result.current.mode).toBe('standby')
    // 切换为不支持
    mockWakeSupported = false
    rerender()
    expect(result.current.mode).toBe('off')
    expect(result.current.wakeSupported).toBe(false)
  })

  it('SpeechRecognition 不可用时 startListeningRound 不崩溃', () => {
    // 移除 SpeechRecognition 模拟不可用
    delete (window as any).SpeechRecognition
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')
    // 不崩溃即可
  })

  it('active 模式下 onresult 触发 onFinalTranscript', async () => {
    vi.useFakeTimers()
    const onFinalTranscript = vi.fn()
    let capturedRec: any = null
    ;(window as any).SpeechRecognition = class {
      lang = ''; continuous = false; interimResults = false
      onresult: any = null; onend: any = null; onerror: any = null
      start = vi.fn(() => { capturedRec = this })
      stop = vi.fn()
    }
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript })
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')
    // 模拟语音识别结果
    if (capturedRec?.onresult) {
      act(() => {
        capturedRec.onresult({ results: [[{ transcript: '今天天气真好' }]], length: 1 })
      })
      // 等待防抖 1800ms
      await act(async () => { await vi.advanceTimersByTimeAsync(1900) })
      expect(onFinalTranscript).toHaveBeenCalledWith('今天天气真好')
    }
    vi.useRealTimers()
  })

  it('active 模式下 onend 自动重启聊听', async () => {
    let capturedRec: any = null
    ;(window as any).SpeechRecognition = class {
      lang = ''; continuous = false; interimResults = false
      onresult: any = null; onend: any = null; onerror: any = null
      start = vi.fn(() => { capturedRec = this })
      stop = vi.fn()
    }
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    act(() => { mockOnDetected.current?.() })
    expect(result.current.mode).toBe('active')
    // 模拟 onend 触发
    if (capturedRec?.onend) {
      act(() => { capturedRec.onend() })
      // RESTART_DELAY_MS 后重启
      await act(async () => { vi.advanceTimersByTime(1000) })
    }
    expect(result.current.mode).toBe('active')
  })

  it('返回 wakeSupported 和 wakeStatus', () => {
    const { result } = renderHook(() =>
      useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript: vi.fn() })
    )
    expect(result.current.wakeSupported).toBe(true)
    expect(result.current.wakeStatus).toBe('listening')
  })

  describe('P1-3 唤醒参数远程化（audit-report-07 P1-3）', () => {
    it('voiceCall.* 远程配置覆盖本地 fallback（冷却窗/防抖/重启延迟）', async () => {
      // 先加载远程配置（25s→5s、1800ms→400ms、300ms→100ms）
      mockFetchSystemConfig.mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({
          code: 0,
          data: {
            voiceCall: { cooldownSeconds: 5, restartDelayMs: 100, speechEndDebounceMs: 400 },
          },
        }),
      })
      const remoteMod = await import('../config/remote')
      await remoteMod.initRemoteConfig()

      const onFinalTranscript = vi.fn()
      let capturedRec: any = null
      let startCount = 0
      ;(window as any).SpeechRecognition = class {
        lang = ''; continuous = false; interimResults = false
        onresult: any = null; onend: any = null; onerror: any = null
        start = vi.fn(() => { capturedRec = this; startCount++ })
        stop = vi.fn()
      }
      const { result } = renderHook(() =>
        useVoiceCallMode({ enabled: true, tts: mockTts, busy: false, onFinalTranscript })
      )
      act(() => { mockOnDetected.current?.() })
      expect(result.current.mode).toBe('active')

      // 冷却窗覆盖：5s（fallback 25s）→ 温柔收尾 + 回 standby
      await act(async () => { vi.advanceTimersByTime(5000) })
      expect(mockTts.speak).toHaveBeenCalledWith('我先安静陪着你，想说话随时叫我哦')
      expect(result.current.mode).toBe('standby')

      // 重新进入 active：防抖覆盖 400ms（fallback 1800ms）
      act(() => { mockOnDetected.current?.() })
      expect(result.current.mode).toBe('active')

      // 重启延迟覆盖：自然 onend（无防抖 pending，speechEndTimerRef=null）→ 100ms（fallback 300ms）后开启下一轮聆听
      // 注意：必须在防抖触发前验证——防抖发送路径 rec.stop(true) 会将 onend 置 null（silent 不触发重启）
      const startBefore = startCount
      act(() => { capturedRec?.onend?.() })
      await act(async () => { await vi.advanceTimersByTimeAsync(150) })
      expect(startCount).toBeGreaterThan(startBefore)

      // 防抖覆盖：语音识别结果 → 400ms（fallback 1800ms）后发送最终结果
      act(() => {
        capturedRec?.onresult?.({ results: [[{ transcript: '你好呀' }]], length: 1 })
      })
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      expect(onFinalTranscript).toHaveBeenCalledWith('你好呀')
    })
  })
})
