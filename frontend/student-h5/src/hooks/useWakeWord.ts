/**
 * 语音唤醒 Hook：Transformers.js + Whisper 本地离线转写（design/28 §1.1/§1.3）
 *
 * 职责：active=true 时启动"唤醒词监听"——
 *      麦克风 → AudioWorklet → 16kHz Float32 → 滑窗累积 → VAD 静音过滤 →
 *      Whisper 本地转写（WASM）→ 文本匹配"哈喽波波"及变体 → 回调 onDetected。
 *      active=false 或组件卸载时立即释放麦克风（模型单例保留，避免重复加载）。
 *
 * 隐私即设计（design/28 §1.4）：
 * - 仅在对话内（ChatRoom 挂载后）由调用方控制 active，离开对话即释放；
 * - 唤醒检测音频全程留在设备本地（Whisper WASM 推理），不上传。
 *
 * 零外部账号：无需任何平台注册 / AccessKey / 商业许可（模型 Apache 2.0）。
 *
 * 降级策略（任一不满足 → supported=false → UI 隐藏语音唤醒开关，按住说话主路径不受影响）：
 * - 浏览器不支持 getUserMedia / AudioWorklet；
 * - 模型下载或推理初始化失败（网络/兼容性错误）。
 *
 * 性能说明：
 * - 模型单例（模块级）：首次约 20MB 下载（HF 镜像 + 浏览器 Cache API 缓存），
 *   之后 standby↔active 频繁切换不重复加载；
 * - 串行识别：上一窗未识别完不提交新窗，识别慢时丢弃过老的积压音频（保最新 2.5s）；
 * - VAD 预过滤：静音窗直接跳过转写，省 CPU 并抑制 Whisper 静音幻觉。
 */
import { useState, useEffect, useRef } from 'react'
import {
  WAKE_MODEL_ID,
  WAKE_MODEL_REMOTE_HOST,
  WAKE_WINDOW_SECONDS,
  WAKE_KEEP_SECONDS,
  SILENCE_RMS_THRESHOLD,
  matchesWakeWord,
  isHallucination,
} from '../config/wakeWord'
import { createPcmCapture, type PcmCaptureHandle } from '../utils/createPcmCapture'
import { createModelStatusStore, type ModelStatus } from '../utils/modelStatusStore'

/** Whisper 要求的采样率 */
const TARGET_SAMPLE_RATE = 16000

/** 滑窗/重叠/积压上限（样本数 @16kHz） */
const WINDOW_SAMPLES = Math.round(WAKE_WINDOW_SECONDS * TARGET_SAMPLE_RATE)
const KEEP_SAMPLES = Math.round(WAKE_KEEP_SECONDS * TARGET_SAMPLE_RATE)
const MAX_BUFFER_SAMPLES = WINDOW_SAMPLES * 2

/** AudioWorklet 处理器代码（保留用于 createPcmCapture 内部，此处不再直接使用） */
// 注：实际采集由 createPcmCapture 统一管理，支持 ScriptProcessor 降级

/** 线性插值降采样到 16kHz（Float32 输出，Whisper 直接消费） */
function downsampleTo16kFloat(f32, inputRate) {
  if (inputRate === TARGET_SAMPLE_RATE) return f32
  const ratio = inputRate / TARGET_SAMPLE_RATE
  const outLen = Math.floor(f32.length / ratio)
  const out = new Float32Array(outLen)
  for (let i = 0; i < outLen; i++) {
    const pos = i * ratio
    const i0 = Math.floor(pos)
    const frac = pos - i0
    out[i] = (f32[i0] || 0) * (1 - frac) + (f32[i0 + 1] || 0) * frac
  }
  return out
}

/** 合并多个 Float32 chunk */
function concatChunks(chunks, total) {
  const merged = new Float32Array(total)
  let offset = 0
  for (const c of chunks) {
    merged.set(c, offset)
    offset += c.length
  }
  return merged
}

/** 均方根能量（VAD 判停用） */
function rms(f32) {
  let sum = 0
  for (let i = 0; i < f32.length; i++) sum += f32[i] * f32[i]
  return Math.sqrt(sum / (f32.length || 1))
}

/* ===== 全局模型加载状态（ARCH-006 收敛 A：复用 createModelStatusStore 基座） ===== */
const wakeModelStore = createModelStatusStore()

function setModelStatus(s: ModelStatus, progress?: number, error?: string) {
  wakeModelStore.setStatus(s, progress, error)
}

/** 订阅唤醒模型加载状态（React Hook，任意组件可调用） */
export const useWakeModelStatus = wakeModelStore.useStatus

/** 非 React 环境读取当前状态（如 console 调试） */
export const getWakeModelStatus = wakeModelStore.getStatus

/** @internal 测试专用：重置模块级单例状态，避免跨测试污染 */
export function __resetWakeWordForTest() {
  transcriberPromise = null
  wakeModelStore.reset()
}

/**
 * 模块级模型单例：加载一次，跨会话复用。
 * 失败时重置为 null，允许下次重试（如网络恢复）。
 *
 * ONNX Runtime WASM 显式指向本机服务器 /mindsafe/ort/（构建时由 vite 插件从 node_modules 复制），
 * 不走 transformers 默认的 jsdelivr CDN（国内不稳定）。变体选择与 transformers 默认一致：
 * Safari/iOS 用 plain，其余（Chrome/Android 等）用 asyncify。
 */
let transcriberPromise = null
function getTranscriber() {
  if (!transcriberPromise) {
    setModelStatus('loading')
    transcriberPromise = (async () => {
      // ━━ 环境前置检查：SharedArrayBuffer + SIMD 是 ORT WASM 的硬性依赖 ━━
      if (typeof SharedArrayBuffer === 'undefined') {
        console.warn('[WakeWord] SharedArrayBuffer不可用，语音功能降级')
        throw Object.assign(new Error('ENV_UNSUPPORTED'), { _unsupported: true })
      }
      let hasSimd = false
      try {
        hasSimd = WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,8,1,6,0,65,0,253,15,11]))
      } catch { /* ignore */ }
      if (!hasSimd) {
        console.warn('[WakeWord] 浏览器不支持WebAssembly SIMD，语音功能降级')
        throw Object.assign(new Error('ENV_UNSUPPORTED'), { _unsupported: true })
      }

      // 动态导入：Transformers.js 独立分包，未启用唤醒时主路径零开销
      const { pipeline, env } = await import('@huggingface/transformers')
      // 模型同源部署：从自己服务器加载（/mindsafe/models/），浏览器 HTTP 缓存持久化
      const base = import.meta.env.BASE_URL || '/'
      // 关键：remoteHost 必须是绝对 URL，否则 get_file_metadata 内的 new URL() 会失败，
      // 导致 preprocessor_config.json 存在性检查返回 false → processor 不加载 → 转写崩溃
      let remoteHost = WAKE_MODEL_REMOTE_HOST === 'SAME_ORIGIN'
        ? `${base}models/`
        : WAKE_MODEL_REMOTE_HOST
      if (remoteHost && !remoteHost.startsWith('http')) {
        remoteHost = window.location.origin + remoteHost
      }
      env.remoteHost = remoteHost
      // 自托管模型不带 /resolve/{revision}/ 路径段
      env.remotePathTemplate = '{model}/'
      env.allowLocalModels = false

      // ━━ 关键修复 1：禁用 WASM 缓存，避免 blob URL 工厂导致 Worker 创建失败 ━━
      env.useWasmCache = false
      // ━━ 关键修复 2：单线程模式，避免 ORT 创建 pthread Worker ━━
      env.backends.onnx.wasm.numThreads = 1

      // 请求持久化存储（防止浏览器在存储压力下清除模型缓存）
      navigator.storage?.persist?.().catch(() => {})
      // ONNX Runtime WASM 走本地（dist/ort/ → /mindsafe/ort/）
      // 始终用非 asyncify 变体（ORT 主代码非 asyncify 编译，asyncify 调用约定不匹配）
      const variant = 'ort-wasm-simd-threaded'
      env.backends.onnx.wasm.wasmPaths = {
        mjs: `${base}ort/${variant}.mjs`,
        wasm: `${base}ort/${variant}.wasm`,
      }
      const t = await pipeline('automatic-speech-recognition', WAKE_MODEL_ID, {
        // 禁用高级图优化：ORT 1.26.0 的 TransposeDQWeightsForMatMulNBits
        // 优化对 int8 QDQ 模型有 bug（缺少 scale 张量导致 session 创建失败）
        session_options: { graphOptimizationLevel: 'basic' },
        progress_callback: (p) => {
          if (p.status === 'progress_total' && typeof p.progress === 'number') {
            // 聚合进度（跨所有文件的总百分比）
            console.debug(`[WakeWord] 模型总进度 ${p.progress.toFixed(0)}%`)
            setModelStatus('loading', Math.round(p.progress))
          } else if (p.status === 'progress' && p.file && typeof p.progress === 'number') {
            console.debug(`[WakeWord] ${p.file} ${p.progress.toFixed(0)}%`)
          }
        },
      })
      setModelStatus('ready')
      return t
    })().catch((err) => {
      transcriberPromise = null
      if (err?._unsupported) {
        setModelStatus('unsupported')
        return null as any
      }
      const errMsg = err?.message || String(err)
      const errStack = err?.stack?.split('\n').slice(0, 3).join(' | ') || ''
      const fullMsg = `${errMsg}${errStack ? ' @ ' + errStack : ''}`
      console.error('[WakeWord] 模型加载失败:', fullMsg, err)
      setModelStatus('error', undefined, fullMsg)
      throw err
    })
  }
  return transcriberPromise
}

/**
 * 预加载模型（不启动麦克风）：在情绪选择页停留时提前下载模型，
 * 进对话时直接就绪。状态可通过 useWakeModelStatus() 订阅查看。
 * 调用时机：EmotionSelect 挂载 + ChatRoom 挂载。
 */
export function preloadWakeModel() {
  getTranscriber().catch(() => {}) // 失败时 active 会重试
}

/**
 * @param {object} opts
 * @param {boolean} opts.active      是否启动引擎（加载模型 + 启动麦克风）——仅对话内且处于待唤醒态时为 true
 * @param {boolean} opts.paused      暂停检测（AI 忙碌时防自听回声，但保持模型 + 麦克风就绪，忙碌结束立即恢复检测）
 * @param {(detection: {label: string, text: string}) => void} opts.onDetected 检测到唤醒词回调
 * @returns {{ supported: boolean, wakeStatus: string }} 环境是否支持 + 当前状态（诊断用）
 */
export function useWakeWord({ active, paused, onDetected }) {
  const [supported, setSupported] = useState(false)
  const [wakeStatus, setWakeStatus] = useState('idle') // idle | loading | listening | error | detected
  const onDetectedRef = useRef(onDetected)
  const pausedRef = useRef(paused)
  useEffect(() => { onDetectedRef.current = onDetected })
  useEffect(() => {
    pausedRef.current = paused
    if (paused) {
      console.debug('[WakeWord] 检测暂停（AI 忙碌，防自听回声）')
    } else {
      console.debug('[WakeWord] 检测恢复（AI 空闲，继续监听唤醒词）')
    }
  }, [paused])

  // 环境探测：仅需麦克风（AudioWorklet 不可用时自动降级 ScriptProcessor）
  useEffect(() => {
    setSupported(!!navigator.mediaDevices?.getUserMedia)
  }, [])

  useEffect(() => {
    if (!active || !supported) return undefined

    let cancelled = false
    let stream: MediaStream | null = null
    let audioCtx: AudioContext | null = null
    let captureHandle: PcmCaptureHandle | null = null
    let iosResumeHandler: (() => void) | null = null
    let worker: Worker | null = null
    let transcribeId = 0
    /** Worker 不可用时降级为主线程推理（确保唤醒功能可用，代价是推理期间短暂阻塞 UI） */
    let useMainThread = false

    // 滑窗状态
    let chunks = []
    let totalSamples = 0
    let analyzing = false

    // 构建 Worker 配置（同源部署路径，绝对 URL 确保 get_file_metadata 的 Range 请求能正确验证文件存在性）
    const base = import.meta.env.BASE_URL || '/'
    // 始终用非 asyncify 变体（ORT 主代码非 asyncify 编译）
    const variant = 'ort-wasm-simd-threaded'
    let workerRemoteHost = WAKE_MODEL_REMOTE_HOST === 'SAME_ORIGIN' ? `${base}models/` : WAKE_MODEL_REMOTE_HOST
    if (workerRemoteHost && !workerRemoteHost.startsWith('http')) {
      workerRemoteHost = window.location.origin + workerRemoteHost
    }
    const workerConfig = {
      modelId: WAKE_MODEL_ID,
      remoteHost: workerRemoteHost,
      wasmPaths: {
        mjs: `${base}ort/${variant}.mjs`,
        wasm: `${base}ort/${variant}.wasm`,
      },
    }

    /** 处理转写结果（Worker 和主线程共用） */
    const handleTranscribeResult = (text: string) => {
      if (cancelled) return
      if (text) {
        const hallucinated = isHallucination(text)
        const matched = matchesWakeWord(text)
        // 降级为 debug：正常运行时不刷屏，需要诊断时开 DevTools verbose 级别即可
        console.debug('[WakeWord] 转写结果:', JSON.stringify(text), '幻觉:', hallucinated, '匹配:', matched)
        if (matched) {
          setWakeStatus('detected')
          setTimeout(() => onDetectedRef.current?.({ label: 'halou-bobo', text }), 300)
        }
      }
      analyzing = false
      maybeAnalyze()
    }

    /** 主线程推理（Worker 不可用时的降级路径） */
    const analyzeOnMainThread = async (audio: Float32Array) => {
      analyzing = true
      try {
        const transcriber = await getTranscriber()
        if (!transcriber || cancelled) { analyzing = false; return }
        const output = await transcriber(audio, { language: 'chinese', task: 'transcribe' })
        handleTranscribeResult(output?.text || '')
      } catch (err) {
        console.warn('[WakeWord] 主线程转写失败:', (err as Error)?.message)
        analyzing = false
        maybeAnalyze()
      }
    }

    /** 识别一个音频窗（Worker 优先，降级主线程） */
    const analyzeWindow = (audio: Float32Array) => {
      analyzing = true
      // 降级为 debug：正常不刷屏
      console.debug(`[WakeWord] 📨 提交音频窗分析 (useMainThread=${useMainThread}, 长度=${audio.length}, rms=${rms(audio).toFixed(5)})`)
      if (useMainThread) {
        analyzeOnMainThread(audio)
        return
      }
      const id = ++transcribeId
      // 将音频数据转移到 Worker（zero-copy，主线程不再持有）
      worker?.postMessage(
        { type: 'transcribe', audio, id, config: workerConfig },
        [audio.buffer],
      )
    }

    /** 尝试提交一个滑窗（累积满 + 未在识别中 才提交；paused 时完全跳过，保留缓冲区供恢复后立即分析） */
    const maybeAnalyze = () => {
      if (cancelled || analyzing) return
      // paused 时不做任何缓冲管理：让音频自然累积到 MAX_BUFFER_SAMPLES 上限，
      // 恢复后下一帧立即满足 >= WINDOW_SAMPLES 条件并提交检测（修复 PC 端唤醒延迟）
      if (pausedRef.current) return
      if (totalSamples < WINDOW_SAMPLES) return
      const merged = concatChunks(chunks, totalSamples)
      const keep = merged.slice(-KEEP_SAMPLES)
      chunks = [keep]
      totalSamples = keep.length
      if (rms(merged) < SILENCE_RMS_THRESHOLD) return
      analyzeWindow(merged)
    }

    /** 切换到主线程推理模式（Worker 失败时调用） */
    const fallbackToMainThread = (reason: string) => {
      if (useMainThread) return
      console.warn('[WakeWord] Worker 不可用，降级主线程推理:', reason)
      useMainThread = true
      worker?.terminate()
      worker = null
      // 触发主线程模型加载（如果尚未加载）
      setModelStatus('loading')
      setWakeStatus('loading')
      getTranscriber().then((t) => {
        if (t && !cancelled) {
          setModelStatus('ready')
          setWakeStatus('listening')
          // 如果缓冲区已有足够音频，立即分析
          maybeAnalyze()
        }
      }).catch((err) => {
        console.error('[WakeWord] 主线程模型也失败:', (err as Error)?.message)
        setModelStatus('error')
        setWakeStatus('error')
      })
    }

    // 创建 Worker 并初始化模型
    try {
      worker = new Worker(
        new URL('../workers/wakeWordWorker.ts', import.meta.url),
        { type: 'module' },
      )
    } catch (err) {
      console.warn('[WakeWord] Worker 创建失败:', (err as Error)?.message)
      fallbackToMainThread('Worker 创建异常')
    }

    if (worker) {
      // Worker 超时保护：15s 内未收到 ready/result → 降级主线程
      const workerTimeout = setTimeout(() => {
        if (!useMainThread && !cancelled) {
          fallbackToMainThread('Worker 15s 无响应')
        }
      }, 15000)

      worker.onmessage = (event) => {
        const { type } = event.data
        if (type === 'status') {
          const { status } = event.data
          if (status === 'loading') {
            if (wakeModelStore.getStatus() !== 'ready') setWakeStatus('loading')
          } else if (status === 'ready') {
            clearTimeout(workerTimeout)
            setModelStatus('ready')
          } else if (status === 'error') {
            clearTimeout(workerTimeout)
            fallbackToMainThread(event.data.message || 'Worker 模型加载失败')
          }
        } else if (type === 'result') {
          clearTimeout(workerTimeout)
          handleTranscribeResult(event.data.text)
        } else if (type === 'error') {
          console.warn('[WakeWord] 转写失败（忽略，下窗重试）:', event.data.message)
          analyzing = false
          maybeAnalyze()
        } else if (type === 'progress') {
          console.debug(`[WakeWord] 模型加载 ${event.data.file} ${event.data.progress.toFixed(0)}%`)
        }
      }

      worker.onerror = (err) => {
        console.warn('[WakeWord] Worker 错误:', err.message)
        fallbackToMainThread(err.message || 'Worker onerror')
      }

      // 发送初始化消息（触发模型加载，若已在情绪页预加载则从 Cache API 秒开）
      setModelStatus('loading')
      worker.postMessage({ type: 'init', config: workerConfig })
    }

    // 启动麦克风
    ;(async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        })
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop())
          return
        }

        audioCtx = new (window.AudioContext || window.webkitAudioContext)()
        if (audioCtx.state === 'suspended') {
          await audioCtx.resume().catch(() => {})
          if (audioCtx.state === 'suspended') {
            iosResumeHandler = () => { audioCtx?.resume().catch(() => {}) }
            document.addEventListener('pointerdown', iosResumeHandler)
          }
        }

        const inputRate = audioCtx.sampleRate
        let chunkCount = 0
        captureHandle = await createPcmCapture(audioCtx, stream, (rawPcm: Float32Array) => {
          if (cancelled) return
          const pcm = downsampleTo16kFloat(rawPcm, inputRate)
          chunks.push(pcm)
          totalSamples += pcm.length
          chunkCount++
          // 每 50 个 chunk 打一次诊断日志（约 4s@48kHz/4096）
          if (chunkCount % 50 === 1) {
            console.debug(`[WakeWord] 音频流入: chunks=${chunkCount}, totalSamples=${totalSamples}/${WINDOW_SAMPLES}, paused=${pausedRef.current}, analyzing=${analyzing}, useMainThread=${useMainThread}`)
          }
          if (totalSamples > MAX_BUFFER_SAMPLES) {
            const mergedAll = concatChunks(chunks, totalSamples)
            const keepLatest = mergedAll.slice(-WINDOW_SAMPLES)
            chunks = [keepLatest]
            totalSamples = keepLatest.length
          }
          maybeAnalyze()
        })

        setWakeStatus('listening')
      } catch (err) {
        console.warn('[WakeWord] 麦克风初始化失败:', err?.message || err)
        setWakeStatus('error')
        stream?.getTracks().forEach((t) => t.stop())
        audioCtx?.close().catch(() => {})
      }
    })()

    // 释放：关闭开关 / 离开对话 / 切出待唤醒态 均走这里
    return () => {
      cancelled = true
      setWakeStatus('idle')
      if (iosResumeHandler) document.removeEventListener('pointerdown', iosResumeHandler)
      captureHandle?.cleanup()
      stream?.getTracks().forEach((t) => t.stop())
      audioCtx?.close().catch(() => {})
      worker?.terminate()
      worker = null
    }
  }, [active, supported])

  return { supported, wakeStatus }
}
