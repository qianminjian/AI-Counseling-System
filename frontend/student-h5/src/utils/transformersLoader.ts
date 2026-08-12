/**
 * DC-009 本地模型加载器双实现收敛（SPEC §23）
 *
 * 收敛 useWakeWord.getTranscriber 与 useVoiceprint.getModelBundle 的重复初始化：
 * 环境检查（SAB/SIMD）/ env 配置（remoteHost/模板/缓存/线程/ort 路径）/ 错误分类与格式化。
 *
 * 单例 + 状态机保留在各 hook；本模块只负责"一次加载流程"。
 */
import type * as HF from '@huggingface/transformers'

/** 环境不支持（无 SharedArrayBuffer / 无 WebAssembly SIMD）——调用方应静默降级，不触发 onError */
export class UnsupportedEnvironmentError extends Error {
  readonly unsupported = true
  constructor(reason: string) {
    super(`ENV_UNSUPPORTED: ${reason}`)
    this.name = 'UnsupportedEnvironmentError'
  }
}

/** ORT SIMD 探测字节码（v128 类型 + i32x4 常量 + f32x4 min 指令，V8 11+/现代浏览器为 true） */
const SIMD_PROBE = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 8, 1, 6, 0, 65, 0, 253, 15, 11])

/** 环境前置检查：SharedArrayBuffer + SIMD 是 ORT WASM 的硬性依赖；不满足抛 UnsupportedEnvironmentError */
export function checkWasmEnvironment(): void {
  if (typeof SharedArrayBuffer === 'undefined') {
    if (import.meta.env.DEV) console.warn('[ModelLoader] SharedArrayBuffer不可用（需 cross-origin isolation），语音功能降级')
    throw new UnsupportedEnvironmentError('SharedArrayBuffer')
  }
  let hasSimd = false
  try {
    hasSimd = WebAssembly.validate(SIMD_PROBE)
  } catch { /* ignore */ }
  if (!hasSimd) {
    if (import.meta.env.DEV) console.warn('[ModelLoader] 浏览器不支持WebAssembly SIMD，语音功能降级')
    throw new UnsupportedEnvironmentError('WebAssembly SIMD')
  }
}

/**
 * 模型远程宿主解析：
 * - 'SAME_ORIGIN' → `${base}models/`（同源部署，浏览器 HTTP 缓存持久化）
 * - 相对路径 → origin 拼接（remoteHost 必须是绝对 URL，否则 get_file_metadata 的 new URL() 失败）
 * - 绝对 URL 原样返回
 * F-15（2026-08-09）：SAME_ORIGIN 分支与 8.2 对齐——8.2 内联实现为
 * `${base}models/` → `window.location.origin +` 绝对化；DC-009 收敛（41a7a67）时漏掉该处理
 * → remoteHost 为相对路径 → transformers.js get_file_metadata 探测/缓存 key 异常
 * → 模型每次重新下载、下载显著变慢（声纹 6.7MB 实测 88s vs 应 ~12s）。
 */
export function buildRemoteHost(base: string, modelHost: string): string {
  if (modelHost === 'SAME_ORIGIN') {
    const host = `${base}models/`
    return host.startsWith('http') ? host : window.location.origin + host
  }
  if (modelHost.startsWith('http')) return modelHost
  return window.location.origin + modelHost
}

export interface LoadTransformersOptions<T> {
  /** 模型宿主：'SAME_ORIGIN' / 相对路径 / 绝对 URL */
  modelHost: string
  /** 模型加载回调（hf 为动态导入的 transformers 模块，调用方自行 pipeline/from_pretrained） */
  load: (hf: typeof HF) => Promise<T>
  /** 聚合进度（progress_total 优先，否则文件平均） */
  onProgress?: (p: number) => void
  /** 非环境不支持错误（message+stack 前 3 行语义见 formatModelError），回调后仍上抛 */
  onError?: (err: unknown) => void
}

/**
 * 一次模型加载流程：checkWasmEnvironment → 动态 import（独立分包，未启用时主路径零开销）
 * → env 配置 → persist → load。
 *
 * 失败语义：UnsupportedEnvironmentError 直接上抛且不触发 onError（环境不支持不算加载失败）；
 * import/load 失败调 onError 后上抛（调用方自行置单例 null 允许重试）。
 */
export async function loadTransformersModel<T>(opts: LoadTransformersOptions<T>): Promise<T> {
  checkWasmEnvironment()
  const base = import.meta.env.BASE_URL || '/'
  try {
    const hf = await import('@huggingface/transformers')
    configureEnv(hf, buildRemoteHost(base, opts.modelHost), base)
    // F-8-Worker 埋点同步：主线程 ORT session_create 耗时（与 Worker 一致，便于诊断降级路径）
    // P2-1（audit-report-07 P2-1）：session_create 轨迹日志降噪 console.info → console.debug
    const t0Session = (typeof performance !== 'undefined' ? performance.now() : Date.now())
    const _tag = (opts.modelHost === 'SAME_ORIGIN' || /wespeaker|vp/i.test(opts.modelHost)) ? '[Voiceprint]' : '[WakeWord]'
    console.debug(`[TS ${(t0Session / 1000).toFixed(2)}s] ${_tag} 主线程 ORT session_create 开始（numThreads=${hf.env.backends.onnx.wasm.numThreads}）`)
    const result = await opts.load(hf)
    const elapsed = Math.round(((typeof performance !== 'undefined' ? performance.now() : Date.now()) - t0Session))
    console.debug(`[TS ${((typeof performance !== 'undefined' ? performance.now() : Date.now()) / 1000).toFixed(2)}s] ${_tag} 主线程 ORT session_create 完成，耗时 ${elapsed}ms`)
    return result
  } catch (err) {
    if (opts.onError) opts.onError(err)
    throw err
  }
}

function configureEnv(hf: typeof HF, remoteHost: string, base: string): void {
  // 自托管模型不带 /resolve/{revision}/ 路径段
  hf.env.remotePathTemplate = '{model}/'
  hf.env.remoteHost = remoteHost
  hf.env.allowLocalModels = false
  // 关键修复 1：禁用 WASM 缓存，避免 blob URL 工厂导致 Worker 创建失败
  hf.env.useWasmCache = false
  // F-8（2026-08-09）：双线程加速 ORT session 创建。macOS Chrome 支持 SharedArrayBuffer + pthread，
  // 单线程 40MB 模型 session 创建耗时长（约 30-60s）→ 改为 2 线程。
  // 兼容回退：低端 CPU/WebView 仍可改回 1。
  hf.env.backends.onnx.wasm.numThreads = 2
  // ONNX Runtime WASM 走本地（dist/ort/ → /mindsafe/ort/）；始终非 asyncify 变体（调用约定匹配）
  const variant = 'ort-wasm-simd-threaded'
  hf.env.backends.onnx.wasm.wasmPaths = {
    mjs: `${base}ort/${variant}.mjs`,
    wasm: `${base}ort/${variant}.wasm`,
  }
  // 请求持久化存储（防止浏览器在存储压力下清除模型缓存）
  navigator.storage?.persist?.().catch(() => {})
}

/**
 * 模型下载进度聚合处理器（传给 pipeline/from_pretrained 的 progress_callback）：
 * 按文件平均聚合（仅处理 status=progress 事件，忽略 progress_total——并行加载多个模型时
 * progress_total 事件会互相覆盖导致进度跳变，如声纹的 AutoModel+AutoFeatureExtractor 并行场景）。
 * F-16（2026-08-09 用户实测）：单调保护——新文件开始下载（progress 从 0 加入 fileProgress）会
 * 拉低平均值导致进度回跳（如 80%→40%，声纹多次反跳）。记录 lastShown 只升不降，进度单调前进。
 */
export function createProgressHandler(onProgress: (p: number) => void): (ev: unknown) => void {
  const fileProgress: Record<string, number> = {}
  let lastShown = 0
  return (ev) => {
    const e = ev as { status?: string; progress?: number; file?: string }
    if (e.status === 'progress' && e.file && typeof e.progress === 'number') {
      fileProgress[e.file] = e.progress
      const files = Object.keys(fileProgress)
      const avg = Math.round(files.reduce((s, f) => s + fileProgress[f], 0) / files.length)
      if (avg >= lastShown) {
        lastShown = avg
        onProgress(avg)
      }
    }
  }
}

/** 模型加载错误格式化：message + stack 前 3 行（' | ' 连接），非 Error 值降级 String() */
export function formatModelError(err: unknown): string {
  const errMsg = (err as { message?: string })?.message || String(err)
  const errStack = (err as { stack?: string })?.stack?.split('\n').slice(0, 3).join(' | ') || ''
  return `${errMsg}${errStack ? ' @ ' + errStack : ''}`
}
