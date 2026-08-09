/**
 * DC-009 本地模型加载器双实现收敛（SPEC §23）
 *
 * 覆盖：checkWasmEnvironment 三分支 / buildRemoteHost 三态 /
 * loadTransformersModel env 配置 + onError 语义 + 环境不支持不触发 onError /
 * createProgressHandler 聚合语义 / formatModelError。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// vi.hoisted：mock 工厂提升后仍可访问的 mock env（断言 env 配置）
const mockEnv = vi.hoisted(() => ({
  remoteHost: '',
  remotePathTemplate: '',
  allowLocalModels: true,
  useWasmCache: true,
  backends: { onnx: { wasm: { numThreads: 4, wasmPaths: {} } } },
}))

vi.mock('@huggingface/transformers', () => ({ env: mockEnv, pipeline: vi.fn() }))

import {
  checkWasmEnvironment,
  buildRemoteHost,
  loadTransformersModel,
  UnsupportedEnvironmentError,
  createProgressHandler,
  formatModelError,
} from './transformersLoader'

/** jsdom/Node 环境统一：显式安装/摘除 SAB，保证三分支可控 */
function stubSAB(present: boolean) {
  if (present) {
    ;(globalThis as any).SharedArrayBuffer = class StubSAB {}
  } else {
    delete (globalThis as any).SharedArrayBuffer
  }
}

describe('checkWasmEnvironment', () => {
  // spyOn 返回类型与 Mock/MockInstance 宽泛类型不兼容（vitest v3 签名差异），测试文件用 any
  let validateSpy: any

  beforeEach(() => {
    stubSAB(true)
    validateSpy = vi.spyOn(WebAssembly, 'validate')
  })

  afterEach(() => {
    validateSpy.mockRestore()
  })

  it('SAB + SIMD 均满足时通过', () => {
    validateSpy.mockReturnValue(true)
    expect(() => checkWasmEnvironment()).not.toThrow()
  })

  it('无 SharedArrayBuffer 抛 UnsupportedEnvironmentError（unsupported=true）', () => {
    stubSAB(false)
    validateSpy.mockReturnValue(true)
    expect(() => checkWasmEnvironment()).toThrow(UnsupportedEnvironmentError)
  })

  it('无 SIMD 抛 UnsupportedEnvironmentError', () => {
    validateSpy.mockReturnValue(false)
    let caught: unknown
    try {
      checkWasmEnvironment()
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(UnsupportedEnvironmentError)
    expect((caught as UnsupportedEnvironmentError).unsupported).toBe(true)
  })
})

describe('buildRemoteHost', () => {
  it("SAME_ORIGIN → origin + base + 'models/'（绝对 URL，F-15 与 8.2 对齐）", () => {
    expect(buildRemoteHost('/', 'SAME_ORIGIN')).toBe('http://localhost:3000/models/')
    expect(buildRemoteHost('/sub/', 'SAME_ORIGIN')).toBe('http://localhost:3000/sub/models/')
  })

  it('相对路径 → origin 拼接', () => {
    expect(buildRemoteHost('/', '/static/models/')).toBe(window.location.origin + '/static/models/')
  })

  it('绝对 URL 原样返回', () => {
    expect(buildRemoteHost('/', 'https://cdn.example.com/models/')).toBe('https://cdn.example.com/models/')
  })
})

describe('loadTransformersModel', () => {
  beforeEach(() => {
    stubSAB(true)
    vi.spyOn(WebAssembly, 'validate').mockReturnValue(true)
    mockEnv.remoteHost = ''
    mockEnv.remotePathTemplate = ''
    mockEnv.allowLocalModels = true
    mockEnv.useWasmCache = true
    mockEnv.backends.onnx.wasm.numThreads = 4
    mockEnv.backends.onnx.wasm.wasmPaths = {}
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('配置 env（remoteHost/模板/禁用本地模型/WASM 缓存/单线程/本地 ort 路径）并调用 load', async () => {
    const load = vi.fn(async () => 'ok')
    const r = await loadTransformersModel({ modelHost: 'SAME_ORIGIN', load })
    expect(r).toBe('ok')
    expect(load).toHaveBeenCalledTimes(1)
    expect(mockEnv.remoteHost).toBe('http://localhost:3000/models/')
    expect(mockEnv.remotePathTemplate).toBe('{model}/')
    expect(mockEnv.allowLocalModels).toBe(false)
    expect(mockEnv.useWasmCache).toBe(false)
    // F-8（2026-08-09）：numThreads 1→2 双线程加速 ORT session 创建
    expect(mockEnv.backends.onnx.wasm.numThreads).toBe(2)
    // vi.hoisted 对象无类型推导，此处显式断言 wasmPaths 契约（mjs/wasm 本地 ort 路径）
    const wasmPaths = mockEnv.backends.onnx.wasm.wasmPaths as { mjs: string; wasm: string }
    expect(wasmPaths.mjs).toContain('/ort/ort-wasm-simd-threaded.mjs')
    expect(wasmPaths.wasm).toContain('/ort/ort-wasm-simd-threaded.wasm')
  })

  it('load 失败 → 调 onError 并上抛', async () => {
    const onError = vi.fn()
    const load = vi.fn(async () => { throw new Error('model download failed') })
    await expect(loadTransformersModel({ modelHost: 'x', load, onError })).rejects.toThrow('model download failed')
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'model download failed' }))
  })

  it('环境不支持 → 抛 UnsupportedEnvironmentError 且不调 onError', async () => {
    stubSAB(false)
    const onError = vi.fn()
    const load = vi.fn(async () => 'never')
    await expect(loadTransformersModel({ modelHost: 'x', load, onError })).rejects.toThrow(UnsupportedEnvironmentError)
    expect(load).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
  })
})

describe('createProgressHandler', () => {
  it('progress_total 事件不处理（并行多模型时互相覆盖，F-16 弃用）', () => {
    const cb = vi.fn()
    const h = createProgressHandler(cb)
    h({ status: 'progress_total', progress: 37 })
    expect(cb).not.toHaveBeenCalled()
  })

  it('progress 文件事件：按文件平均聚合', () => {
    const cb = vi.fn()
    const h = createProgressHandler(cb)
    h({ status: 'progress', file: 'a.onnx', progress: 40 })
    h({ status: 'progress', file: 'b.onnx', progress: 60 })
    expect(cb).toHaveBeenLastCalledWith(50)
  })

  it('F-16 单调保护：新文件加入拉低平均值时不回调（进度不回跳）', () => {
    const cb = vi.fn()
    const h = createProgressHandler(cb)
    h({ status: 'progress', file: 'a.onnx', progress: 80 })
    expect(cb).toHaveBeenLastCalledWith(80)
    // 新文件 b 从 0 开始 → 平均 (80+0)/2=40 < 80 → 不回调（防 80%→40% 回跳）
    h({ status: 'progress', file: 'b.onnx', progress: 0 })
    expect(cb).toHaveBeenCalledTimes(1)
    // a 90 + b 50 → 平均 70 < 80 → 仍不回调
    h({ status: 'progress', file: 'a.onnx', progress: 90 })
    h({ status: 'progress', file: 'b.onnx', progress: 50 })
    expect(cb).toHaveBeenCalledTimes(1)
    // a 100 + b 80 → 平均 90 >= 80 → 回调 90
    h({ status: 'progress', file: 'a.onnx', progress: 100 })
    h({ status: 'progress', file: 'b.onnx', progress: 80 })
    expect(cb).toHaveBeenLastCalledWith(90)
  })

  it('无 progress 数值的事件不触发回调', () => {
    const cb = vi.fn()
    const h = createProgressHandler(cb)
    h({ status: 'done' })
    expect(cb).not.toHaveBeenCalled()
  })
})

describe('formatModelError', () => {
  it('含 message + stack 前 3 行（| 连接）', () => {
    const err = new Error('boom')
    err.stack = 'Error: boom\n  at a\n  at b\n  at c\n  at d'
    expect(formatModelError(err)).toBe('boom @ Error: boom |   at a |   at b')
  })

  it('无 stack 时仅 message；非 Error 值降级 String()', () => {
    expect(formatModelError({ message: 'no-stack' })).toBe('no-stack')
    expect(formatModelError('raw string')).toBe('raw string')
  })
})
