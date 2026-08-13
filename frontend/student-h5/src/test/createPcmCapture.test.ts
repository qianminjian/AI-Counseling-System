import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPcmCapture, isMicSupported } from '../utils/createPcmCapture'

describe('createPcmCapture', () => {
  let mockCtx: any
  let mockStream: any
  let mockSource: any

  beforeEach(() => {
    mockSource = { connect: vi.fn(), disconnect: vi.fn() }
    mockCtx = {
      createMediaStreamSource: vi.fn(() => mockSource),
      createScriptProcessor: vi.fn(() => ({
        onaudioprocess: null,
        connect: vi.fn(),
        disconnect: vi.fn(),
      })),
      createGain: vi.fn(() => ({
        gain: { value: 1 },
        connect: vi.fn(),
        disconnect: vi.fn(),
      })),
      destination: {},
      audioWorklet: undefined,
    }
    mockStream = {}
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:mock'),
      revokeObjectURL: vi.fn(),
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('无 AudioWorklet 时降级为 ScriptProcessor（engine=script）', async () => {
    // audioWorklet 不存在
    const handle = await createPcmCapture(mockCtx, mockStream, vi.fn())
    expect(handle.engine).toBe('script')
    expect(mockCtx.createScriptProcessor).toHaveBeenCalledWith(4096, 1, 1)
  })

  it('ScriptProcessor 模式：cleanup 断开连接', async () => {
    const processor = {
      onaudioprocess: null as any,
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    mockCtx.createScriptProcessor = vi.fn(() => processor)
    const gain = { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }
    mockCtx.createGain = vi.fn(() => gain)

    const handle = await createPcmCapture(mockCtx, mockStream, vi.fn())
    handle.cleanup()
    expect(processor.onaudioprocess).toBeNull()
    expect(processor.disconnect).toHaveBeenCalled()
    expect(gain.disconnect).toHaveBeenCalled()
    expect(mockSource.disconnect).toHaveBeenCalled()
  })

  it('ScriptProcessor 模式：onaudioprocess 回调传递 PCM 数据', async () => {
    const onPcm = vi.fn()
    const processor = {
      onaudioprocess: null as any,
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    mockCtx.createScriptProcessor = vi.fn(() => processor)

    await createPcmCapture(mockCtx, mockStream, onPcm)
    // 模拟音频处理事件
    const fakePcm = new Float32Array([0.1, 0.2, 0.3])
    processor.onaudioprocess({ inputBuffer: { getChannelData: () => fakePcm } })
    expect(onPcm).toHaveBeenCalledWith(expect.any(Float32Array))
    expect(onPcm.mock.calls[0][0]).toEqual(fakePcm)
  })

  it('AudioWorklet 可用时优先使用 worklet（engine=worklet）', async () => {
    const mockWorkletNode = {
      port: { onmessage: null as any, close: vi.fn() },
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    vi.stubGlobal('AudioWorkletNode', vi.fn(function () { return mockWorkletNode }))
    mockCtx.audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) }

    const handle = await createPcmCapture(mockCtx, mockStream, vi.fn())
    expect(handle.engine).toBe('worklet')
    expect(mockCtx.audioWorklet.addModule).toHaveBeenCalled()
  })

  it('AudioWorklet 失败时降级为 ScriptProcessor', async () => {
    vi.stubGlobal('AudioWorkletNode', vi.fn(function () { throw new Error('fail') }))
    mockCtx.audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) }

    const handle = await createPcmCapture(mockCtx, mockStream, vi.fn())
    expect(handle.engine).toBe('script')
  })

  it('worklet 模式：port.onmessage 传递 PCM', async () => {
    const onPcm = vi.fn()
    const mockWorkletNode = {
      port: { onmessage: null as any, close: vi.fn() },
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    vi.stubGlobal('AudioWorkletNode', vi.fn(function () { return mockWorkletNode }))
    mockCtx.audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) }

    await createPcmCapture(mockCtx, mockStream, onPcm)
    const pcm = new Float32Array([0.5, 0.6])
    mockWorkletNode.port.onmessage({ data: pcm })
    expect(onPcm).toHaveBeenCalledWith(pcm)
  })

  it('worklet cleanup 关闭 port 并断开 source', async () => {
    const mockWorkletNode = {
      port: { onmessage: null as any, close: vi.fn() },
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    vi.stubGlobal('AudioWorkletNode', vi.fn(function () { return mockWorkletNode }))
    mockCtx.audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) }

    const handle = await createPcmCapture(mockCtx, mockStream, vi.fn())
    handle.cleanup()
    expect(mockWorkletNode.port.close).toHaveBeenCalled()
    expect(mockSource.disconnect).toHaveBeenCalled()
  })
})

describe('isMicSupported', () => {
  it('有 getUserMedia 返回 true', () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
    expect(isMicSupported()).toBe(true)
  })

  it('无 mediaDevices 返回 false', () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: undefined,
      writable: true,
      configurable: true,
    })
    expect(isMicSupported()).toBe(false)
  })
})
