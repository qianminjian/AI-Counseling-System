/**
 * 唤醒词检测 Web Worker：将 Whisper WASM 推理从主线程移出，避免 UI 卡顿。
 *
 * 通信协议：
 * - 主线程 → Worker: { type: 'init', config } | { type: 'transcribe', audio: Float32Array, id }
 * - Worker → 主线程: { type: 'status', status } | { type: 'result', id, text } | { type: 'error', message }
 *
 * 模型单例：Worker 生命周期内只加载一次，跨多次 transcribe 复用。
 */

import type { AutomaticSpeechRecognitionPipeline } from '@huggingface/transformers'

const TAG = '[WakeWordWorker]'

/** F-25 轨迹时间戳日志：Worker 内 performance.now() 与主线程同时间轴（页面启动后相对秒） */
const tslog = (...args: unknown[]) => {
  console.info(`[TS ${(performance.now() / 1000).toFixed(2)}s ${new Date().toLocaleTimeString('zh-CN', { hour12: false })}]`, ...args)
}

// 捕获所有未处理错误（诊断用）
self.onerror = (e: any) => {
  console.error(TAG, '未捕获错误:', e.message, e.filename, e.lineno)
}
self.onunhandledrejection = (e) => {
  console.error(TAG, '未处理 Promise 拒绝:', e.reason)
}

// FE-003：单例显式类型（initPromise 失败重置为 null 允许重试）
let transcriberInstance: AutomaticSpeechRecognitionPipeline | null = null
let initPromise: Promise<AutomaticSpeechRecognitionPipeline> | null = null

async function ensureModel(config) {
  if (transcriberInstance) return transcriberInstance
  if (initPromise) return initPromise

  initPromise = (async () => {
    tslog('Worker ensureModel 开始（loading 消息已发送）')
    self.postMessage({ type: 'status', status: 'loading' })

    // ━━ 环境前置检查：SharedArrayBuffer 是 ORT WASM 的硬性依赖 ━━
    if (typeof SharedArrayBuffer === 'undefined') {
      console.error(TAG, '❌ SharedArrayBuffer 不可用')
      throw new Error('浏览器不支持 SharedArrayBuffer（需要 COOP/COEP 响应头 ）')
    }
    const { pipeline, env } = await import('@huggingface/transformers')

    // 同源部署配置（与主线程 getTranscriber 保持一致）
    // 关键：remoteHost 必须是绝对 URL，否则 get_file_metadata 内的 new URL() 会失败，
    // 导致 preprocessor_config.json 存在性检查返回 false → processor 不加载 → 转写崩溃
    let remoteHost = config.remoteHost
    if (remoteHost && !remoteHost.startsWith('http')) {
      remoteHost = self.location.origin + remoteHost
    }
    env.remoteHost = remoteHost
    env.remotePathTemplate = '{model}/'
    env.allowLocalModels = false

    // ━━ 关键修复 1：禁用 WASM 缓存，避免 blob URL 工厂导致 Worker 创建失败 ━━
    env.useWasmCache = false
    // F-8-Worker（2026-08-09）：与主线程 transformersLoader.ts 同步——单线程 40MB 模型 session_create
    // 耗时长（30-60s）改双线程加速。注意 numThreads=2 需要 SharedArrayBuffer + pthread
    // （COOP/COEP 头已配齐），低端 CPU/WebView 异常时可改回 1。
    env.backends.onnx.wasm!.numThreads = 2

    // ONNX WASM 路径
    env.backends.onnx.wasm!.wasmPaths = config.wasmPaths

    // F-8-Worker 诊断：埋点 ORT session_create 耗时（生产环境 console 可见，便于排查加载慢问题）
    const t0Session = Date.now()
    tslog('Worker ORT session_create 开始')
    console.info(TAG, `开始 ORT session_create（numThreads=${env.backends.onnx.wasm!.numThreads}）`)
    const t = await pipeline('automatic-speech-recognition', config.modelId, {
      // 禁用高级图优化：ORT 1.26.0 TransposeDQWeightsForMatMulNBits bug
      session_options: { graphOptimizationLevel: 'basic' },
      progress_callback: (p) => {
        if (p.status === 'progress' && p.file && typeof p.progress === 'number') {
          self.postMessage({ type: 'progress', file: p.file, progress: p.progress })
        }
      },
    })
    const sessionCreateMs = Date.now() - t0Session
    tslog(`Worker ORT session_create 完成，耗时 ${sessionCreateMs}ms`)
    console.info(TAG, `✅ ORT session_create 完成，耗时 ${sessionCreateMs}ms`)
    self.postMessage({ type: 'session_created', durationMs: sessionCreateMs })

    transcriberInstance = t
    tslog('Worker ready 消息已发送')
    self.postMessage({ type: 'status', status: 'ready' })
    return t
  })().catch((err) => {
    initPromise = null
    const msg = err?.message || String(err)
    const stack = err?.stack?.split('\n').slice(0, 5).join(' | ') || ''
    console.error(TAG, '❌ 模型初始化失败:', msg, stack)
    self.postMessage({ type: 'status', status: 'error', message: `${msg}${stack ? ' @ ' + stack : ''}` })
    throw err
  })

  return initPromise
}

self.onmessage = async (event) => {
  const { type } = event.data
  console.debug(TAG, '收到消息:', type)

  if (type === 'init') {
    tslog('Worker 收到 init 消息，开始模型加载')
    try {
      await ensureModel(event.data.config)
    } catch {
      // 错误已通过 postMessage 发送
    }
    return
  }

  if (type === 'transcribe') {
    const { audio, id } = event.data
    tslog(`Worker 收到 transcribe id=${id}, 音频长度=${audio?.length}`)
    console.debug(TAG, `转写请求 id=${id}, 音频长度=${audio?.length}`)
    try {
      const transcriber = await ensureModel(event.data.config)
      const output = await transcriber(audio, { language: 'chinese', task: 'transcribe' })
      // FE-003：HF 返回 output[]|output 联合，text 属性仅在单对象上；断言收窄类型，运行时仍是 output?.text，行为不变
      const text = (output as unknown as { text?: string })?.text || ''
      tslog(`Worker 转写完成 id=${id}:`, text)
      console.debug(TAG, `转写完成 id=${id}:`, text)
      self.postMessage({ type: 'result', id, text })
    } catch (err) {
      console.error(TAG, `转写异常 id=${id}:`, err?.message)
      self.postMessage({ type: 'error', id, message: err?.message || String(err) })
    }
    return
  }
}
