import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

// vi.hoisted 确保变量在 vi.mock 工厂提升后仍可访问
const { mockTranscriber, mockCaptureCleanup, mockCreatePcmCapture } = vi.hoisted(() => ({
  mockTranscriber: vi.fn(),
  mockCaptureCleanup: vi.fn(),
  mockCreatePcmCapture: vi.fn(),
}))

vi.mock('@huggingface/transformers', () => ({
  pipeline: vi.fn().mockResolvedValue(mockTranscriber),
  env: {
    remoteHost: '',
    allowLocalModels: false,
    backends: { onnx: { wasm: { wasmPaths: {} } } },
  },
}))

vi.mock('../utils/createPcmCapture', () => ({
  createPcmCapture: (...args: any[]) => mockCreatePcmCapture(...args),
}))

import { useWakeWord, __resetWakeWordForTest } from '../hooks/useWakeWord'

describe('useWakeWord', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    __resetWakeWordForTest()
    mockCreatePcmCapture.mockResolvedValue({ engine: 'worklet', cleanup: mockCaptureCleanup })
    mockTranscriber.mockResolvedValue({ text: '' })
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop: vi.fn() }] }) },
      writable: true,
      configurable: true,
    })
    ;(window as any).AudioContext = vi.fn().mockImplementation(() => ({
      state: 'running',
      sampleRate: 16000,
      resume: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
    }))
  })

  afterEach(() => {
    delete (window as any).AudioContext
  })

  it('无麦克风时 supported=false', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: undefined, writable: true, configurable: true,
    })
    const { result } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.supported).toBe(false)
    expect(result.current.wakeStatus).toBe('idle')
  })

  it('有麦克风时 supported=true', async () => {
    const { result } = renderHook(() => useWakeWord({ active: false, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.supported).toBe(true)
  })

  it('active=false 时保持 idle', async () => {
    const { result } = renderHook(() => useWakeWord({ active: false, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.wakeStatus).toBe('idle')
  })

  it('active=true 时加载模型并进入 listening', async () => {
    const { result } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => {
      expect(result.current.wakeStatus).toBe('listening')
    })
    expect(result.current.supported).toBe(true)
  })

  it('卸载时释放资源', async () => {
    const { result, unmount } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))
    unmount()
    expect(mockCaptureCleanup).toHaveBeenCalled()
  })

  it('模型加载失败时 wakeStatus=error', async () => {
    // 让 pipeline 抛异常模拟模型下载失败
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockRejectedValueOnce(new Error('model download failed'))
    const { result } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => {
      expect(result.current.wakeStatus).toBe('error')
    })
  })

  it('音频采集触发转写：检测到唤醒词时调用 onDetected', async () => {
    mockTranscriber.mockResolvedValue({ text: '哈喽波波' })
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    // 模拟音频数据输入（需要超过 WINDOW_SAMPLES = 3.5s * 16000 = 56000 samples）
    // 使用高振幅数据确保 RMS > SILENCE_RMS_THRESHOLD
    const bigChunk = new Float32Array(60000).fill(0.5)
    await act(async () => {
      pcmCallback(bigChunk)
      // 等待异步转写完成 + 300ms onDetected setTimeout
      await new Promise((r) => setTimeout(r, 400))
    })

    expect(mockTranscriber).toHaveBeenCalled()
    expect(onDetected).toHaveBeenCalledWith(expect.objectContaining({ label: 'halou-bobo' }))
  })

  it('静音音频不触发转写（VAD 过滤）', async () => {
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    // 极低振幅（静音）
    const silentChunk = new Float32Array(60000).fill(0.0001)
    await act(async () => {
      pcmCallback(silentChunk)
      await new Promise((r) => setTimeout(r, 50))
    })

    // 转写器不应被调用（VAD 过滤了静音）
    expect(mockTranscriber).not.toHaveBeenCalled()
    expect(onDetected).not.toHaveBeenCalled()
  })

  it('转写结果非唤醒词时不触发 onDetected', async () => {
    mockTranscriber.mockResolvedValue({ text: '今天天气真好' })
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    const bigChunk = new Float32Array(60000).fill(0.5)
    await act(async () => {
      pcmCallback(bigChunk)
      await new Promise((r) => setTimeout(r, 50))
    })

    expect(mockTranscriber).toHaveBeenCalled()
    expect(onDetected).not.toHaveBeenCalled()
  })

  it('转写失败时不崩溃，等待下一窗', async () => {
    mockTranscriber.mockRejectedValue(new Error('inference failed'))
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    const bigChunk = new Float32Array(60000).fill(0.5)
    await act(async () => {
      pcmCallback(bigChunk)
      await new Promise((r) => setTimeout(r, 50))
    })

    // 不崩溃，状态仍为 listening
    expect(result.current.wakeStatus).toBe('listening')
    expect(onDetected).not.toHaveBeenCalled()
  })

  it('转写失败（非 Error 类型）不崩溃，覆盖 err?.message || err 分支', async () => {
    // 拒绝值为字符串（无 .message 属性）→ 走 || err 分支
    mockTranscriber.mockRejectedValue('raw string error')
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    const bigChunk = new Float32Array(60000).fill(0.5)
    await act(async () => {
      pcmCallback(bigChunk)
      await new Promise((r) => setTimeout(r, 50))
    })
    expect(result.current.wakeStatus).toBe('listening')
  })

  it('积压超限时丢弃旧音频（保留最新一窗）', async () => {
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })
    // 让转写器挂起（模拟识别慢）
    let resolveTranscribe: any
    mockTranscriber.mockImplementation(() => new Promise((r) => { resolveTranscribe = r }))

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    // 输入大量数据超过 MAX_BUFFER_SAMPLES (WINDOW_SAMPLES * 2 = 112000)
    const hugeChunk = new Float32Array(120000).fill(0.5)
    await act(async () => {
      pcmCallback(hugeChunk)
      await new Promise((r) => setTimeout(r, 10))
    })

    // 应该只触发一次转写（串行）
    expect(mockTranscriber).toHaveBeenCalledTimes(1)
    // 解决挂起的 promise
    resolveTranscribe({ text: '' })
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
  })

  it('降采样：非 16kHz 输入时正确转换', async () => {
    // 设置 sampleRate 为 48000（触发降采样路径）
    ;(window as any).AudioContext = vi.fn().mockImplementation(() => ({
      state: 'running',
      sampleRate: 48000,
      resume: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
    }))
    mockTranscriber.mockResolvedValue({ text: '哈喽波波' })
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreatePcmCapture.mockImplementation(async (_ctx: any, _stream: any, cb: any) => {
      pcmCallback = cb
      return { engine: 'worklet', cleanup: mockCaptureCleanup }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))

    // 48kHz 输入，需要 3x 的样本数才能达到 16kHz 的 WINDOW_SAMPLES
    const chunk48k = new Float32Array(180000).fill(0.5)
    await act(async () => {
      pcmCallback(chunk48k)
      // 等待异步转写完成 + 300ms onDetected setTimeout
      await new Promise((r) => setTimeout(r, 400))
    })

    expect(mockTranscriber).toHaveBeenCalled()
    expect(onDetected).toHaveBeenCalledWith(expect.objectContaining({ label: 'halou-bobo' }))
  })

  it('iOS AudioContext suspended 时注册 pointerdown 监听', async () => {
    const addEventSpy = vi.spyOn(document, 'addEventListener')
    ;(window as any).AudioContext = vi.fn().mockImplementation(() => ({
      state: 'suspended',
      sampleRate: 16000,
      resume: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
    }))

    const { result } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))
    // resume 后仍为 suspended → 注册 pointerdown
    expect(addEventSpy).toHaveBeenCalledWith('pointerdown', expect.any(Function))
    addEventSpy.mockRestore()
  })

  it('模型加载后取消（getUserMedia 解析前 unmount）释放轨道', async () => {
    const stopFn = vi.fn()
    let resolveGetUserMedia: (v: any) => void = () => {}
    ;(navigator.mediaDevices.getUserMedia as any).mockImplementation(
      () => new Promise(r => { resolveGetUserMedia = r })
    )
    // 让 pipeline 挂起，保证 wakeStatus 停留在 loading
    const { pipeline } = await import('@huggingface/transformers')
    let resolvePipeline: (v: any) => void = () => {}
    ;(pipeline as any).mockImplementationOnce(() => new Promise(r => { resolvePipeline = r }))

    const { result, unmount } = renderHook(() => useWakeWord({ active: true, onDetected: vi.fn() }))
    // pipeline 挂起 → wakeStatus 应为 loading
    await waitFor(() => expect(result.current.wakeStatus).toBe('loading'))

    // unmount 触发 cancelled = true
    unmount()

    // 解析 pipeline + getUserMedia → 进入 if (cancelled) 分支，停止轨道
    await act(async () => {
      resolvePipeline(mockTranscriber)
      await new Promise(r => setTimeout(r, 10))
      resolveGetUserMedia({ getTracks: () => [{ stop: stopFn }] })
      await new Promise(r => setTimeout(r, 10))
    })
    expect(stopFn).toHaveBeenCalled()
  })
})

describe('useWakeWord 模块级错误路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreatePcmCapture.mockResolvedValue({ engine: 'worklet', cleanup: vi.fn() })
    mockTranscriber.mockResolvedValue({ text: '' })
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop: vi.fn() }] }) },
      writable: true,
      configurable: true,
    })
    ;(window as any).AudioContext = vi.fn().mockImplementation(() => ({
      state: 'running',
      sampleRate: 16000,
      resume: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
    }))
  })

  afterEach(() => {
    delete (window as any).AudioContext
  })

  it('pipeline 加载失败时 transcriberPromise 重置，wakeStatus=error', async () => {
    vi.resetModules()
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockRejectedValueOnce(new Error('model download failed'))

    const mod = await import('../hooks/useWakeWord')
    const { result, unmount } = renderHook(() => mod.useWakeWord({ active: true, onDetected: vi.fn() }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('error'))
    unmount()
  })

  it('progress_callback 被调用时输出调试日志', async () => {
    vi.resetModules()
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {})
    let capturedOpts: any = null
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockImplementationOnce(async (_task: any, _model: any, opts: any) => {
      capturedOpts = opts
      // 调用 progress_callback 覆盖分支
      opts.progress_callback({ status: 'progress', file: 'whisper.onnx', progress: 75 })
      opts.progress_callback({ status: 'done' }) // 不满足条件，不输出
      return mockTranscriber
    })

    const mod = await import('../hooks/useWakeWord')
    const { result, unmount } = renderHook(() => mod.useWakeWord({ active: true, onDetected: vi.fn() }))
    // 等待流程完成或失败（progress_callback 已在 pipeline 调用时触发）
    await waitFor(() => {
      expect(['listening', 'error']).toContain(result.current.wakeStatus)
    })
    expect(capturedOpts).toBeTruthy()
    expect(debugSpy).toHaveBeenCalledWith('[WakeWord] whisper.onnx 75%')
    unmount()
    debugSpy.mockRestore()
    infoSpy.mockRestore()
  })
})
