import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAudioRecorder } from '../hooks/useAudioRecorder'

// Mock MediaRecorder
class MockMediaRecorder {
  static isTypeSupported = vi.fn((type: string) => type === 'audio/webm')
  ondataavailable: any = null
  onstop: any = null
  state = 'inactive'
  start = vi.fn(() => { this.state = 'recording' })
  stop = vi.fn(() => {
    this.state = 'inactive'
    // 触发 dataavailable + stop
    this.ondataavailable?.({ data: new Blob(['audio'], { type: 'audio/webm' }) })
    this.onstop?.()
  })
}

describe('useAudioRecorder', () => {
  let mockStream: any

  beforeEach(() => {
    vi.stubGlobal('MediaRecorder', MockMediaRecorder)
    mockStream = {
      getTracks: () => [{ readyState: 'live', stop: vi.fn() }],
    }
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn().mockResolvedValue(mockStream) },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('初始状态：非录音、非分析、支持', () => {
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    expect(result.current.recording).toBe(false)
    expect(result.current.analyzing).toBe(false)
    expect(result.current.supported).toBe(true)
  })

  it('麦克风和语音识别都不可用时 supported=false', () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: undefined,
      writable: true,
      configurable: true,
    })
    // 确保 SpeechRecognition 也不存在
    delete (window as any).SpeechRecognition
    delete (window as any).webkitSpeechRecognition

    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    expect(result.current.supported).toBe(false)
  })

  it('startRecording 设置 recording=true', async () => {
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    await act(async () => { await result.current.startRecording() })
    expect(result.current.recording).toBe(true)
  })

  it('stopRecording 触发 onComplete(blob)', async () => {
    const onComplete = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useAudioRecorder(onComplete))

    await act(async () => { await result.current.startRecording() })
    expect(result.current.recording).toBe(true)

    await act(async () => { result.current.stopRecording() })
    expect(result.current.recording).toBe(false)
    // onstop 是异步的，等 analyzing 结束
    await act(async () => { await vi.waitFor(() => expect(onComplete).toHaveBeenCalled()) })
    expect(onComplete).toHaveBeenCalledWith(expect.any(Blob))
  })

  it('cancelRecording 不触发 onComplete', async () => {
    const onComplete = vi.fn()
    const { result } = renderHook(() => useAudioRecorder(onComplete))

    await act(async () => { await result.current.startRecording() })
    act(() => { result.current.cancelRecording() })
    expect(result.current.recording).toBe(false)
    // 等一小段时间确认 onComplete 不被调用
    await act(async () => { await new Promise(r => setTimeout(r, 50)) })
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('未录音时 stopRecording 无效', () => {
    const onComplete = vi.fn()
    const { result } = renderHook(() => useAudioRecorder(onComplete))
    act(() => { result.current.stopRecording() })
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('warmUp 预热麦克风流', async () => {
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    await act(async () => { await result.current.warmUp() })
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({ audio: true })
  })

  it('warmUp 已有活跃流时跳过', async () => {
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    await act(async () => { await result.current.warmUp() })
    await act(async () => { await result.current.warmUp() })
    // 只调用一次
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledTimes(1)
  })

  it('releaseStream 停止所有轨道', async () => {
    const stopFn = vi.fn()
    mockStream.getTracks = () => [{ readyState: 'live', stop: stopFn }]
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    await act(async () => { await result.current.warmUp() })
    act(() => { result.current.releaseStream() })
    expect(stopFn).toHaveBeenCalled()
  })

  it('麦克风权限拒绝时降级（recording 仍为 true 但无 MediaRecorder）', async () => {
    ;(navigator.mediaDevices.getUserMedia as any).mockRejectedValue(new Error('NotAllowedError'))
    const onComplete = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useAudioRecorder(onComplete))

    await act(async () => { await result.current.startRecording() })
    expect(result.current.recording).toBe(true)

    // stopRecording 走无录音分支 → onComplete(null)
    await act(async () => { result.current.stopRecording() })
    await act(async () => { await vi.waitFor(() => expect(onComplete).toHaveBeenCalledWith(null)) })
  })

  it('无支持的 MIME 类型时用默认格式创建 MediaRecorder', async () => {
    // isTypeSupported 全部返回 false → getSupportedMimeType 返回 ''
    MockMediaRecorder.isTypeSupported = vi.fn(() => false) as any
    vi.stubGlobal('MediaRecorder', MockMediaRecorder)
    const onComplete = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useAudioRecorder(onComplete))

    await act(async () => { await result.current.startRecording() })
    expect(result.current.recording).toBe(true)
    await act(async () => { result.current.stopRecording() })
    await act(async () => { await vi.waitFor(() => expect(onComplete).toHaveBeenCalled()) })
    // 恢复
    MockMediaRecorder.isTypeSupported = vi.fn((type: string) => type === 'audio/webm')
  })

  it('warmUp 失败时不崩溃（catch 分支）', async () => {
    ;(navigator.mediaDevices.getUserMedia as any).mockRejectedValue(new Error('warmup denied'))
    const { result } = renderHook(() => useAudioRecorder(vi.fn()))
    await act(async () => { await result.current.warmUp() })
    // 不抛错，流未获取
    expect(result.current.recording).toBe(false)
  })

  it('MediaRecorder.isTypeSupported 不存在时返回空字符串', async () => {
    class NoTypeRecorder {
      static isTypeSupported = undefined
      ondataavailable: any = null
      onstop: any = null
      start = vi.fn()
      stop = vi.fn(() => { this.onstop?.() })
    }
    vi.stubGlobal('MediaRecorder', NoTypeRecorder)
    const onComplete = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useAudioRecorder(onComplete))
    await act(async () => { await result.current.startRecording() })
    expect(result.current.recording).toBe(true)
    await act(async () => { result.current.stopRecording() })
    await act(async () => { await vi.waitFor(() => expect(onComplete).toHaveBeenCalled()) })
  })
})
