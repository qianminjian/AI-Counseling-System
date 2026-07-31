/**
 * 唤醒词检测 Web Worker：将 Whisper WASM 推理从主线程移出，避免 UI 卡顿。
 *
 * 通信协议：
 * - 主线程 → Worker: { type: 'init', config } | { type: 'transcribe', audio: Float32Array, id }
 * - Worker → 主线程: { type: 'status', status } | { type: 'result', id, text } | { type: 'error', message }
 *
 * 模型单例：Worker 生命周期内只加载一次，跨多次 transcribe 复用。
 */

let transcriberInstance = null
let initPromise = null

async function ensureModel(config) {
  if (transcriberInstance) return transcriberInstance
  if (initPromise) return initPromise

  initPromise = (async () => {
    self.postMessage({ type: 'status', status: 'loading' })

    const { pipeline, env } = await import('@huggingface/transformers')

    // 同源部署配置（与主线程 getTranscriber 保持一致）
    env.remoteHost = config.remoteHost
    env.allowLocalModels = false

    // ONNX WASM 路径
    env.backends.onnx.wasm.wasmPaths = config.wasmPaths

    const t = await pipeline('automatic-speech-recognition', config.modelId, {
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
    self.postMessage({ type: 'status', status: 'error', message: err?.message || String(err) })
    throw err
  })

  return initPromise
}

self.onmessage = async (event) => {
  const { type } = event.data

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
    try {
      const transcriber = await ensureModel(event.data.config)
      const output = await transcriber(audio, { language: 'chinese', task: 'transcribe' })
      self.postMessage({ type: 'result', id, text: output?.text || '' })
    } catch (err) {
      self.postMessage({ type: 'error', id, message: err?.message || String(err) })
    }
    return
  }
}
