import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

// vi.hoisted 确保变量在 vi.mock 工厂提升后仍可访问
const { mockTranscriber, mockSessionStop, mockCreateMicSession } = vi.hoisted(() => ({
  mockTranscriber: vi.fn(),
  mockSessionStop: vi.fn(),
  mockCreateMicSession: vi.fn(),
}))

vi.mock('@huggingface/transformers', () => ({
  pipeline: vi.fn().mockResolvedValue(mockTranscriber),
  env: {
    remoteHost: '',
    allowLocalModels: false,
    backends: { onnx: { wasm: { wasmPaths: {} } } },
  },
}))

vi.mock('../utils/micSession', () => ({
  createMicSession: (...args: any[]) => mockCreateMicSession(...args),
}))

import { useWakeWord, __resetWakeWordForTest } from '../hooks/useWakeWord'

describe('useWakeWord', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    __resetWakeWordForTest()
    mockCreateMicSession.mockResolvedValue({
      engine: 'worklet',
      stop: mockSessionStop,
      ctx: { sampleRate: 16000 },
      stream: {},
    })
    mockTranscriber.mockResolvedValue({ text: '' })
    // supported 探测依赖 mediaDevices 存在（会话已 mock，无需真实 getUserMedia 行为）
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })

  it('无麦克风时 supported=false', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: undefined, writable: true, configurable: true,
    })
    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.supported).toBe(false)
    expect(result.current.wakeStatus).toBe('idle')
  })

  it('有麦克风时 supported=true', async () => {
    const { result } = renderHook(() => useWakeWord({ active: false, paused: false, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.supported).toBe(true)
  })

  it('active=false 时保持 idle', async () => {
    const { result } = renderHook(() => useWakeWord({ active: false, paused: false, onDetected: vi.fn() }))
    await act(async () => {})
    expect(result.current.wakeStatus).toBe('idle')
  })

  it('active=true 时加载模型并进入 listening', async () => {
    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    await waitFor(() => {
      expect(result.current.wakeStatus).toBe('listening')
    })
    expect(result.current.supported).toBe(true)
  })

  it('卸载时释放资源', async () => {
    const { result, unmount } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    await waitFor(() => expect(result.current.wakeStatus).toBe('listening'))
    unmount()
    expect(mockSessionStop).toHaveBeenCalled()
  })

  it('模型加载失败时 wakeStatus=error', async () => {
    // 让 pipeline 抛异常模拟模型下载失败
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockRejectedValueOnce(new Error('model download failed'))
    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    await waitFor(() => {
      expect(result.current.wakeStatus).toBe('error')
    })
  })

  it('音频采集触发转写：检测到唤醒词时调用 onDetected', async () => {
    mockTranscriber.mockResolvedValue({ text: '哈喽波波' })
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
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
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} }
    })
    // 让转写器挂起（模拟识别慢）
    let resolveTranscribe: any
    mockTranscriber.mockImplementation(() => new Promise((r) => { resolveTranscribe = r }))

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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
    mockTranscriber.mockResolvedValue({ text: '哈喽波波' })
    const onDetected = vi.fn()
    let pcmCallback: (pcm: Float32Array) => void = () => {}
    mockCreateMicSession.mockImplementation(async (onPcm: any) => {
      pcmCallback = onPcm
      // 48kHz 采样率（触发降采样路径）
      return { engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 48000 }, stream: {} }
    })

    const { result } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected }))
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

  it('模型加载后取消（createMicSession 解析前 unmount）释放会话', async () => {
    let resolveSession: (v: any) => void = () => {}
    mockCreateMicSession.mockImplementation(
      () => new Promise(r => { resolveSession = r })
    )
    // 让 pipeline 挂起，保证 wakeStatus 停留在 loading
    const { pipeline } = await import('@huggingface/transformers')
    let resolvePipeline: (v: any) => void = () => {}
    ;(pipeline as any).mockImplementationOnce(() => new Promise(r => { resolvePipeline = r }))

    const { result, unmount } = renderHook(() => useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    // pipeline 挂起 → wakeStatus 应为 loading
    await waitFor(() => expect(result.current.wakeStatus).toBe('loading'))

    // unmount 触发 cancelled = true
    unmount()

    // 解析 pipeline + createMicSession → 进入 cancelled 分支，立即释放会话
    await act(async () => {
      resolvePipeline(mockTranscriber)
      await new Promise(r => setTimeout(r, 10))
      resolveSession({ engine: 'worklet', stop: mockSessionStop, ctx: { sampleRate: 16000 }, stream: {} })
      await new Promise(r => setTimeout(r, 10))
    })
    expect(mockSessionStop).toHaveBeenCalled()
  })
})

describe('useWakeWord 模块级错误路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreateMicSession.mockResolvedValue({
      engine: 'worklet',
      stop: vi.fn(),
      ctx: { sampleRate: 16000 },
      stream: {},
    })
    mockTranscriber.mockResolvedValue({ text: '' })
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })


  it('pipeline 加载失败时 transcriberPromise 重置，wakeStatus=error', async () => {
    vi.resetModules()
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockRejectedValueOnce(new Error('model download failed'))

    const mod = await import('../hooks/useWakeWord')
    const { result, unmount } = renderHook(() => mod.useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
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
    const { result, unmount } = renderHook(() => mod.useWakeWord({ active: true, paused: false, onDetected: vi.fn() }))
    // 等待流程完成或失败（progress_callback 已在 pipeline 调用时触发）
    await waitFor(() => {
      expect(['listening', 'error']).toContain(result.current.wakeStatus)
    })
    expect(capturedOpts).toBeTruthy()
    // AUD-027：dbg helper 前缀 + 可变参数（console.debug('[WakeWord]', ...args)）
    // DC-009：进度经 createProgressHandler 聚合为总进度（SPEC §23），断言同步更新
    expect(debugSpy).toHaveBeenCalledWith('[WakeWord]', '模型总进度 75%')
    unmount()
    debugSpy.mockRestore()
    infoSpy.mockRestore()
  })
})
