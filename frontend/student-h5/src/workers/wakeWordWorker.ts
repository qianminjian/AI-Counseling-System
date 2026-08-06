/**
 * 唤醒词检测 Web Worker：将 Whisper WASM 推理从主线程移出，避免 UI 卡顿。
 *
 * 通信协议：
 * - 主线程 → Worker: { type: 'init', config } | { type: 'transcribe', audio: Float32Array, id }
 * - Worker → 主线程: { type: 'status', status } | { type: 'result', id, text } | { type: 'error', message }
 *
 * 模型单例：Worker 生命周期内只加载一次，跨多次 transcribe 复用。
 */

const TAG = '[WakeWordWorker]'

// 捕获所有未处理错误（诊断用）
self.onerror = (e: any) => {
  console.error(TAG, '未捕获错误:', e.message, e.filename, e.lineno)
}
self.onunhandledrejection = (e) => {
  console.error(TAG, '未处理 Promise 拒绝:', e.reason)
}

let transcriberInstance = null
let initPromise = null

async function ensureModel(config) {
  if (transcriberInstance) return transcriberInstance
  if (initPromise) return initPromise

  initPromise = (async () => {
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
    // ━━ 关键修复 2：单线程模式，避免 ORT 创建 pthread Worker ━━
    env.backends.onnx.wasm.numThreads = 1

    // ONNX WASM 路径
    env.backends.onnx.wasm.wasmPaths = config.wasmPaths

    const t = await pipeline('automatic-speech-recognition', config.modelId, {
      // 禁用高级图优化：ORT 1.26.0 TransposeDQWeightsForMatMulNBits bug
      session_options: { graphOptimizationLevel: 'basic' },
      progress_callback: (p) => {
        if (p.status === 'progress' && p.file && typeof p.progress === 'number') {
          self.postMessage({ type: 'progress', file: p.file, progress: p.progress })
        }
      },
    })

    transcriberInstance = t
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
    try {
      await ensureModel(event.data.config)
    } catch {
      // 错误已通过 postMessage 发送
    }
    return
  }

  if (type === 'transcribe') {
    const { audio, id } = event.data
    console.debug(TAG, `转写请求 id=${id}, 音频长度=${audio?.length}`)
    try {
      const transcriber = await ensureModel(event.data.config)
      const output = await transcriber(audio, { language: 'chinese', task: 'transcribe' })
      console.debug(TAG, `转写完成 id=${id}:`, output?.text)
      self.postMessage({ type: 'result', id, text: output?.text || '' })
    } catch (err) {
      console.error(TAG, `转写异常 id=${id}:`, err?.message)
      self.postMessage({ type: 'error', id, message: err?.message || String(err) })
    }
    return
  }
}
