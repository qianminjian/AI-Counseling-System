import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

// ━━ 环境前置：jsdom 无 SharedArrayBuffer / crossOriginIsolated，需手动补齐 ━━
beforeEach(() => {
  if (typeof globalThis.SharedArrayBuffer === 'undefined') {
    Object.defineProperty(globalThis, 'SharedArrayBuffer', { value: ArrayBuffer, writable: true, configurable: true })
  }
  Object.defineProperty(globalThis, 'crossOriginIsolated', { value: true, writable: true, configurable: true })
})

// mock transformers（AutoModel + AutoFeatureExtractor 模式）
const mockModel = vi.fn()
const mockFeatureExtractor = vi.fn()
const mockModelFromPretrained = vi.fn().mockResolvedValue(mockModel)
const mockFeatureExtractorFromPretrained = vi.fn().mockResolvedValue(mockFeatureExtractor)

vi.mock('@huggingface/transformers', () => ({
  AutoModel: { from_pretrained: (...args: any[]) => mockModelFromPretrained(...args) },
  AutoFeatureExtractor: { from_pretrained: (...args: any[]) => mockFeatureExtractorFromPretrained(...args) },
  env: {
    remoteHost: '',
    remotePathTemplate: '',
    allowLocalModels: false,
    useWasmCache: true,
    backends: { onnx: { wasm: { wasmPaths: {}, numThreads: 4 } } },
  },
}))

const mockGetAllVoiceprints = vi.fn()
vi.mock('../utils/voiceprintStore', () => ({
  getAllVoiceprints: () => mockGetAllVoiceprints(),
}))

import { useVoiceprint } from '../hooks/useVoiceprint'

// 生成非静音音频（rms 足够大）
const loudAudio = () => new Float32Array(1600).fill(0.5)
const silentAudio = () => new Float32Array(1600).fill(0.0001)

describe('useVoiceprint', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // 默认推理行为：featureExtractor → input_features，model → embeddings tensor
    mockFeatureExtractor.mockResolvedValue({ input_features: [] })
    mockModel.mockResolvedValue({ embeddings: { dims: [1, 2], data: new Float32Array([3, 4]) } })
    mockModelFromPretrained.mockResolvedValue(mockModel)
    mockFeatureExtractorFromPretrained.mockResolvedValue(mockFeatureExtractor)
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })

  it('初始 supported=false, loading=false', () => {
    const { result } = renderHook(() => useVoiceprint())
    expect(result.current.supported).toBe(false)
    expect(result.current.loading).toBe(false)
  })

  it('checkSupport 环境支持时返回 true', async () => {
    const { result } = renderHook(() => useVoiceprint())
    let ok = false
    await act(async () => { ok = await result.current.checkSupport() })
    expect(ok).toBe(true)
    expect(result.current.supported).toBe(true)
  })

  it('checkSupport 无麦克风时返回 false', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: undefined, writable: true, configurable: true,
    })
    const { result } = renderHook(() => useVoiceprint())
    let ok = true
    await act(async () => { ok = await result.current.checkSupport() })
    expect(ok).toBe(false)
  })

  it('extractEmbedding 静音返回 null', async () => {
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(silentAudio(), 16000) })
    expect(emb).toBeNull()
    expect(mockModel).not.toHaveBeenCalled()
  })

  it('extractEmbedding 成功提取并归一化', async () => {
    // embeddings tensor [3,4] → norm=5 → [0.6, 0.8]
    mockModel.mockResolvedValue({ embeddings: { dims: [1, 2], data: new Float32Array([3, 4]) } })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).not.toBeNull()
    expect(emb[0]).toBeCloseTo(0.6)
    expect(emb[1]).toBeCloseTo(0.8)
  })

  it('extractEmbedding 推理失败返回 null', async () => {
    mockModel.mockRejectedValue(new Error('model error'))
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 降采样（非 16kHz 输入）', async () => {
    mockModel.mockResolvedValue({ embeddings: { dims: [1, 2], data: new Float32Array([1, 0]) } })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 48000) })
    expect(emb).not.toBeNull()
    expect(mockModel).toHaveBeenCalled()
  })

  it('verify 无注册声纹返回 matched=false', async () => {
    mockGetAllVoiceprints.mockResolvedValue([])
    const { result } = renderHook(() => useVoiceprint())
    let res: any = null
    await act(async () => { res = await result.current.verify([[1, 0]]) })
    expect(res.matched).toBe(false)
    expect(res.score).toBe(0)
  })

  it('verify 匹配成功返回 userId', async () => {
    mockGetAllVoiceprints.mockResolvedValue([
      { userId: 'u1', pseudonym: '小明', embeddings: [[1, 0]] },
    ])
    const { result } = renderHook(() => useVoiceprint())
    let res: any = null
    await act(async () => { res = await result.current.verify([[1, 0]]) })
    expect(res.matched).toBe(true)
    expect(res.userId).toBe('u1')
    expect(res.pseudonym).toBe('小明')
    expect(res.score).toBeCloseTo(1)
  })

  it('verify 不匹配（相似度过低）', async () => {
    mockGetAllVoiceprints.mockResolvedValue([
      { userId: 'u1', pseudonym: '小明', embeddings: [[1, 0]] },
    ])
    const { result } = renderHook(() => useVoiceprint())
    let res: any = null
    await act(async () => { res = await result.current.verify([[0, 1]]) })
    expect(res.matched).toBe(false)
    expect(res.userId).toBeUndefined()
  })

  it('extractEmbedding 输出无有效字段时返回 null', async () => {
    // 无 embeddings/last_hidden_state/logits 且非数组 → null
    mockModel.mockResolvedValue({ something_else: 12345 })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 全零向量（norm=0）返回 null', async () => {
    mockModel.mockResolvedValue({ embeddings: { dims: [1, 3], data: new Float32Array([0, 0, 0]) } })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 支持 last_hidden_state 3D tensor（mean pooling）', async () => {
    // [batch=1, seq=2, hidden=2] → mean pooling → [1.5, 2.5] → normalized
    mockModel.mockResolvedValue({
      last_hidden_state: { dims: [1, 2, 2], data: new Float32Array([1, 2, 2, 3]) },
    })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).not.toBeNull()
    // mean = [1.5, 2.5], norm = sqrt(2.25+6.25) = sqrt(8.5)
    const norm = Math.sqrt(1.5 * 1.5 + 2.5 * 2.5)
    expect(emb[0]).toBeCloseTo(1.5 / norm)
    expect(emb[1]).toBeCloseTo(2.5 / norm)
  })

  it('extractEmbedding 支持裸数组格式', async () => {
    mockModel.mockResolvedValue([1, 0])
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).not.toBeNull()
    expect(emb[0]).toBeCloseTo(1)
  })

  it('checkSupport 第二次调用直接返回缓存结果', async () => {
    const { result } = renderHook(() => useVoiceprint())
    let ok1 = false, ok2 = false
    await act(async () => { ok1 = await result.current.checkSupport() })
    await act(async () => { ok2 = await result.current.checkSupport() })
    expect(ok1).toBe(true)
    expect(ok2).toBe(true)
  })
})

describe('useVoiceprint 模块级错误路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFeatureExtractor.mockResolvedValue({ input_features: [] })
    mockModel.mockResolvedValue({ embeddings: { dims: [1, 2], data: new Float32Array([3, 4]) } })
    mockModelFromPretrained.mockResolvedValue(mockModel)
    mockFeatureExtractorFromPretrained.mockResolvedValue(mockFeatureExtractor)
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('模型加载失败时 extractEmbedding 返回 null（catch 重置 modelBundlePromise）', async () => {
    vi.resetModules()
    mockModelFromPretrained.mockRejectedValueOnce(new Error('download failed'))

    const mod = await import('../hooks/useVoiceprint')
    const { result } = renderHook(() => mod.useVoiceprint())
    let emb: any = 'x'
    await act(async () => {
      emb = await result.current.extractEmbedding(new Float32Array(1600).fill(0.5), 16000)
    })
    expect(emb).toBeNull()
  })

  it('progress_callback 被调用时输出调试日志', async () => {
    vi.resetModules()
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
    mockModelFromPretrained.mockImplementationOnce(async (_id: any, opts: any) => {
      opts.progress_callback({ status: 'progress', file: 'model.onnx', progress: 50 })
      opts.progress_callback({ status: 'init' }) // 不满足条件，不输出
      return mockModel
    })

    const mod = await import('../hooks/useVoiceprint')
    const { result } = renderHook(() => mod.useVoiceprint())
    let emb: any = null
    await act(async () => {
      emb = await result.current.extractEmbedding(new Float32Array(1600).fill(0.5), 16000)
    })
    expect(emb).not.toBeNull()
    // F-17：双通道独立回调，模型文件通道日志为"模型 X%"
    expect(debugSpy).toHaveBeenCalledWith('[Voiceprint] 模型 50%')
    debugSpy.mockRestore()
  })
})
