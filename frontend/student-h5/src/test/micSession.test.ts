import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// 可控 mock（vi.hoisted 确保提升安全）
const { mockCaptureCleanup } = vi.hoisted(() => ({ mockCaptureCleanup: vi.fn() }))

vi.mock('../utils/createPcmCapture', () => ({
  createPcmCapture: vi.fn(async () => ({ engine: 'worklet' as const, cleanup: mockCaptureCleanup })),
}))

import { mapMicError, getMicStream, createMicSession, MIC_CONSTRAINTS } from '../utils/micSession'
import { createPcmCapture } from '../utils/createPcmCapture'

/** 构造可控 AudioContext mock */
function mockCtx(overrides: Record<string, unknown> = {}) {
  const ctx = {
    state: 'running',
    sampleRate: 16000,
    resume: vi.fn().mockResolvedValue(undefined),
    close: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
  ;(window as any).AudioContext = vi.fn(() => ctx)
  return ctx as any
}

function mockMediaDevices(stream?: any) {
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia: vi.fn().mockResolvedValue(stream || { getTracks: () => [{ stop: vi.fn() }] }) },
    writable: true,
    configurable: true,
  })
}

describe('mapMicError 错误映射（权限生命周期可测契约）', () => {
  it('NotAllowedError → not-allowed（权限拒绝）', () => {
    expect(mapMicError(new DOMException('denied', 'NotAllowedError')).kind).toBe('not-allowed')
  })
  it('NotFoundError → not-found（设备缺失）', () => {
    expect(mapMicError(new DOMException('no device', 'NotFoundError')).kind).toBe('not-found')
  })
  it('SecurityError → security（非安全上下文）', () => {
    expect(mapMicError(new DOMException('insecure', 'SecurityError')).kind).toBe('security')
  })
  it('其他错误 → unknown', () => {
    expect(mapMicError(new Error('boom')).kind).toBe('unknown')
    expect(mapMicError('raw string').kind).toBe('unknown')
  })
})

describe('getMicStream', () => {
  beforeEach(() => { mockMediaDevices() })

  it('以统一约束请求麦克风', async () => {
    const stream = await getMicStream()
    expect(navigator.mediaDevices.getUserMedia).toHaveBeenCalledWith({ audio: MIC_CONSTRAINTS })
    expect(stream).toBeTruthy()
  })

  it('失败时抛出带 kind 的错误', async () => {
    ;(navigator.mediaDevices.getUserMedia as any).mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    await expect(getMicStream()).rejects.toMatchObject({ kind: 'not-allowed' })
  })
})

describe('createMicSession', () => {
  beforeEach(() => {
    mockCtx()
    mockMediaDevices()
    mockCaptureCleanup.mockClear()
  })

  afterEach(() => {
    delete (window as any).AudioContext
  })

  it('启动 PCM 采集会话并透传回调', async () => {
    const onPcm = vi.fn()
    const session = await createMicSession(onPcm)
    expect(session.engine).toBe('worklet')
    expect(session.ctx.sampleRate).toBe(16000)
    // 回调透传给 createPcmCapture
    const pcm = new Float32Array(8)
    const cb = (vi.mocked(createPcmCapture).mock.calls[0][2])
    cb(pcm)
    expect(onPcm).toHaveBeenCalledWith(pcm)
  })

  it('stop 释放采集节点 + 麦克风 + AudioContext（释放单点）', async () => {
    const trackStop = vi.fn()
    mockMediaDevices({ getTracks: () => [{ stop: trackStop }] })
    const ctx = mockCtx()
    const session = await createMicSession(() => {})
    session.stop()
    expect(mockCaptureCleanup).toHaveBeenCalled()
    expect(trackStop).toHaveBeenCalled()
    expect(ctx.close).toHaveBeenCalled()
  })

  it('iOS suspended：resume 后仍挂起 → 注册 pointerdown 兜底；stop 时移除', async () => {
    const addSpy = vi.spyOn(document, 'addEventListener')
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    // resume 后仍保持 suspended（模拟 iOS 限制）
    mockCtx({ state: 'suspended' })
    const session = await createMicSession(() => {})
    expect(addSpy).toHaveBeenCalledWith('pointerdown', expect.any(Function))
    session.stop()
    expect(removeSpy).toHaveBeenCalledWith('pointerdown', expect.any(Function))
    addSpy.mockRestore()
    removeSpy.mockRestore()
  })

  it('createPcmCapture 挂载失败时释放已获取资源并抛错', async () => {
    const trackStop = vi.fn()
    mockMediaDevices({ getTracks: () => [{ stop: trackStop }] })
    const ctx = mockCtx()
    ;(vi.mocked(createPcmCapture) as any).mockRejectedValueOnce(new Error('worklet failed'))
    await expect(createMicSession(() => {})).rejects.toThrow('worklet failed')
    expect(trackStop).toHaveBeenCalled()
    expect(ctx.close).toHaveBeenCalled()
  })

  it('getUserMedia 拒绝时不创建 AudioContext 且抛映射错误', async () => {
    ;(navigator.mediaDevices.getUserMedia as any).mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    await expect(createMicSession(() => {})).rejects.toMatchObject({ kind: 'not-allowed' })
    expect(window.AudioContext).not.toHaveBeenCalled()
  })
})
