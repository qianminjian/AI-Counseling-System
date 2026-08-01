import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

// mock transformers 动态导入
const mockExtractor = vi.fn()
vi.mock('@huggingface/transformers', () => ({
  pipeline: vi.fn().mockResolvedValue(mockExtractor),
  env: {
    remoteHost: '',
    allowLocalModels: false,
    backends: { onnx: { wasm: { wasmPaths: {} } } },
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
    expect(mockExtractor).not.toHaveBeenCalled()
  })

  it('extractEmbedding 成功提取并归一化', async () => {
    mockExtractor.mockResolvedValue({ data: [3, 4] }) // norm=5 → [0.6, 0.8]
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).not.toBeNull()
    expect(emb[0]).toBeCloseTo(0.6)
    expect(emb[1]).toBeCloseTo(0.8)
  })

  it('extractEmbedding 推理失败返回 null', async () => {
    mockExtractor.mockRejectedValue(new Error('model error'))
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 降采样（非 16kHz 输入）', async () => {
    mockExtractor.mockResolvedValue({ data: [1, 0] })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 48000) })
    expect(emb).not.toBeNull()
    expect(mockExtractor).toHaveBeenCalled()
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
    // 输入与模板完全一致 → cosine=1 ≥ 阈值
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
    // 正交向量 → cosine=0
    await act(async () => { res = await result.current.verify([[0, 1]]) })
    expect(res.matched).toBe(false)
    expect(res.userId).toBeUndefined()
  })

  it('extractEmbedding 返回非数组时返回 null', async () => {
    // data 非数组 → Array.isArray 为 false → line 165 return null
    mockExtractor.mockResolvedValue({ data: 12345 })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 全零向量（norm=0）返回 null', async () => {
    // norm=0 → norm > 0 为 false → return null
    mockExtractor.mockResolvedValue({ data: [0, 0, 0] })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = 'x'
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).toBeNull()
  })

  it('extractEmbedding 支持 embedding 字段格式', async () => {
    // 测试 result?.embedding 分支
    mockExtractor.mockResolvedValue({ embedding: [3, 4] })
    const { result } = renderHook(() => useVoiceprint())
    let emb: any = null
    await act(async () => { emb = await result.current.extractEmbedding(loudAudio(), 16000) })
    expect(emb).not.toBeNull()
    expect(emb[0]).toBeCloseTo(0.6)
  })

  it('extractEmbedding 支持裸数组格式', async () => {
    // 测试 || result 分支（无 data/embedding 字段）
    mockExtractor.mockResolvedValue([1, 0])
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
    expect(ok2).toBe(true) // initRef.current=true → 直接返回 supported
  })
})

describe('useVoiceprint 模块级错误路径', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia: vi.fn() },
      writable: true,
      configurable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('pipeline 加载失败时 extractEmbedding 返回 null（catch 重置 extractorPromise）', async () => {
    vi.resetModules()
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockRejectedValueOnce(new Error('download failed'))

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
    const { pipeline } = await import('@huggingface/transformers')
    ;(pipeline as any).mockImplementationOnce(async (_task: any, _model: any, opts: any) => {
      // 模拟 Transformers.js 调用 progress_callback
      opts.progress_callback({ status: 'progress', file: 'model.onnx', progress: 50 })
      opts.progress_callback({ status: 'init' }) // 不满足条件，不输出
      return mockExtractor
    })

    const mod = await import('../hooks/useVoiceprint')
    const { result } = renderHook(() => mod.useVoiceprint())
    let emb: any = null
    mockExtractor.mockResolvedValue({ data: [3, 4] })
    await act(async () => {
      emb = await result.current.extractEmbedding(new Float32Array(1600).fill(0.5), 16000)
    })
    expect(emb).not.toBeNull()
    expect(debugSpy).toHaveBeenCalledWith('[Voiceprint] 模型加载 model.onnx 50%')
    debugSpy.mockRestore()
  })
})
