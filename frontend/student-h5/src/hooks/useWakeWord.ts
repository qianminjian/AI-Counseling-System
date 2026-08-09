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
import { createMicSession, type MicSessionHandle } from '../utils/micSession'
import { createModelStatusStore, type ModelStatus } from '../utils/modelStatusStore'
// DC-009：Transformers.js 初始化收敛到共享 loader（SPEC §23）
import { loadTransformersModel, createProgressHandler, formatModelError } from '../utils/transformersLoader'
// FA-12：滑窗长度/静音阈值走远程配置（wakeWord.*），本地常量仅作 fallback
import { getConfigValue } from '../config/remote'

/** Whisper 要求的采样率 */
const TARGET_SAMPLE_RATE = 16000

/**
 * AUD-027：调试日志 DEV 条件包裹（生产零噪音；诊断时 DEV 模式/本地开发可见）。
 * 运行时降级/失败信号仍走 console.warn（运维可观测），不受此开关影响。
 */
/** F-25 轨迹时间戳日志：所有关键动作带相对/绝对时间（生产可见，诊断用） */
const tslog = (...args: unknown[]) => {
  console.info(`[TS ${(performance.now() / 1000).toFixed(2)}s ${new Date().toLocaleTimeString('zh-CN', { hour12: false })}]`, ...args)
}

const dbg = (...args: unknown[]) => {
  if (import.meta.env.DEV) console.debug('[WakeWord]', ...args)
}

/** 滑窗重叠保留（样本数 @16kHz）：本地常量（远程无对应键），每次转写后保留尾部作下一窗前缀 */
const KEEP_SAMPLES = Math.round(WAKE_KEEP_SECONDS * TARGET_SAMPLE_RATE)

/**
 * FA-12：滑窗长度/静音阈值运行时参数（来自远程配置，挂载时取一次）
 * 音频帧回调热路径经 ref 读取，避免渲染闭包捕获过期常量；
 * 远程无值 / 未加载 → getConfigValue fallback 到本地常量，行为与旧版一致
 */
function resolveWakeParams() {
  const windowSeconds = getConfigValue('wakeWord.windowSeconds', WAKE_WINDOW_SECONDS)
  const windowSamples = Math.round(windowSeconds * TARGET_SAMPLE_RATE)
  return {
    windowSamples,
    maxBufferSamples: windowSamples * 2,
    silenceRms: getConfigValue('wakeWord.silenceRmsThreshold', SILENCE_RMS_THRESHOLD),
  }
}

/** 采集由 utils/micSession 统一管理（createMicSession：约束/错误映射/iOS 兜底/释放单点） */
// 注：AudioWorklet 优先 / ScriptProcessor 降级逻辑在 createPcmCapture 内部

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
function concatChunks(chunks: Float32Array[], total: number): Float32Array {
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

/** ASR 转写器最小可调用契约（HF pipeline 返回值结构，仅约束本模块用到的面） */
type TranscriberFn = (audio: Float32Array, opts: { language: string; task: string }) => Promise<{ text?: string }>

/**
 * 模块级模型单例（主线程转写器）：加载一次，跨会话复用。
 * 失败时重置为 null，允许下次重试（如网络恢复）。
 */
let transcriberPromise: Promise<TranscriberFn> | null = null
function getTranscriber() {
  if (!transcriberPromise) {
    setModelStatus('loading')
    transcriberPromise = loadTransformersModel({
      modelHost: WAKE_MODEL_REMOTE_HOST,
      load: async ({ pipeline }) => {
        const t = await pipeline('automatic-speech-recognition', WAKE_MODEL_ID, {
          session_options: { graphOptimizationLevel: 'basic' },
          progress_callback: createProgressHandler((p) => {
            dbg(`模型总进度 ${p}%`)
            setModelStatus('loading', p)
          }),
        })
        // F-9b 回滚（2026-08-09）：恢复 8.2 行为——不做 pipeline 完整性硬校验
        setModelStatus('ready')
        return t
      },
      onError: (err) => {
        const fullMsg = formatModelError(err)
        console.error('[WakeWord] 模型加载失败:', fullMsg, err)
        setModelStatus('error', undefined, fullMsg)
      },
    })
      .catch((err) => {
        transcriberPromise = null
        if (err?.unsupported) {
          setModelStatus('unsupported')
          return null
        }
        throw err
      })
  }
  return transcriberPromise
}

/**
 * F-19（2026-08-10 用户要求"转写器提前启动"）：模块级 Worker 单例预启动。
 * 模型文件预加载（preloadWakeModel）同时预创建+init 转写 Worker（不启动麦克风，隐私合规
 * design/28 §1.4——监听仍只在对话内），进对话时复用已就绪 Worker → standby 无等待。
 * init 前等待主线程模型下载完成（复用 hasWakeModelInCache/waitForMainThreadModel），避免双下载。
 */
let wakeWorkerPromise: Promise<Worker> | null = null
function buildWorkerConfig() {
  const base = import.meta.env.BASE_URL || '/'
  const variant = 'ort-wasm-simd-threaded'
  let workerRemoteHost = WAKE_MODEL_REMOTE_HOST === 'SAME_ORIGIN' ? `${base}models/` : WAKE_MODEL_REMOTE_HOST
  if (workerRemoteHost && !workerRemoteHost.startsWith('http')) {
    workerRemoteHost = window.location.origin + workerRemoteHost
  }
  return {
    modelId: WAKE_MODEL_ID,
    remoteHost: workerRemoteHost,
    wasmPaths: {
      mjs: `${base}ort/${variant}.mjs`,
      wasm: `${base}ort/${variant}.wasm`,
    },
  }
}
function getWakeWorker(): Promise<Worker> {
  tslog('getWakeWorker 调用')
  if (!wakeWorkerPromise) {
    wakeWorkerPromise = (async () => {
      // F-24（2026-08-10）：Worker 只等模型文件写入缓存（下载完成）即可 init，
      // 不再等主线程 pipeline 完全就绪——主线程 getTranscriber 的 ORT 初始化可能
      // 很慢（声纹 107s 级），Worker 被无辜拖住（UI 长期"正在准备"）。
      // 文件进缓存后 Worker 独立从缓存加载（session_create 0.4-12s），与主线程并行。
      const waitT0 = Date.now()
      tslog('getWakeWorker 开始：检查缓存完整性')
      while (!(await hasWakeModelInCache())) {
        tslog('缓存未完整，等待下载...（已等 ' + Math.round((Date.now() - waitT0) / 1000) + 's）')
        if (Date.now() - waitT0 > 120000) throw new Error('唤醒模型下载超时（120s）')
        await new Promise((r) => setTimeout(r, 2000))
      }
      tslog('缓存完整，创建 Worker')
      const w = new Worker(
        new URL('../workers/wakeWordWorker.ts', import.meta.url),
        { type: 'module' },
      )
      // F-22（2026-08-10 设计落地）：等 Worker 自身 ready（挂临时 onmessage），
      // 而非轮询主线程 wakeModelStore——主线程 getTranscriber 与 Worker 独立，
      // 用主线程状态判 Worker 就绪会导致：主线程失败时 Worker 误超时降级、
      // 主线程先就绪时 resolve 过早（Worker 仍在加载）。
      const t0 = Date.now()
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
          reject(new Error('Worker 预启动超时（90s）'))
        }, 90000)
        w.onmessage = (e) => {
          const { type } = e.data
          if (type === 'status' && e.data.status === 'ready') {
            clearTimeout(timer)
            resolve()
          } else if (type === 'status' && e.data.status === 'error') {
            clearTimeout(timer)
            reject(new Error(e.data.message || 'Worker 加载失败'))
          }
        }
        w.onerror = (err) => {
          clearTimeout(timer)
          reject(new Error(err.message || 'Worker onerror'))
        }
      })
      console.info(`[WakeWord] Worker 预启动就绪（${Math.round((Date.now() - t0) / 1000)}s）`)
      return w
    })().catch((err) => {
      wakeWorkerPromise = null
      throw err
    })
  }
  return wakeWorkerPromise
}

/**
 * 预加载模型（不启动麦克风）：在情绪选择页停留时提前下载模型，
 * 进对话时直接就绪。状态可通过 useWakeModelStatus() 订阅查看。
 * 调用时机：EmotionSelect 挂载 + ChatRoom 挂载。
 * F-19：同时预启动转写 Worker（模型下载 + Worker 初始化都在对话前完成）。
 */
export function preloadWakeModel() {
  tslog('preloadWakeModel 调用（getTranscriber + getWakeWorker）')
  getTranscriber().catch(() => {}) // 失败时 active 会重试
  getWakeWorker().catch(() => {}) // Worker 预启动失败时 active 降级主线程
}

/**
 * F-6/F-9（2026-08-09）：检查唤醒模型是否已完整写入 transformers-cache（Cache API）。
 * encoder + decoder 双文件校验（decoder 30M 是瓶颈文件）。缓存完整时 Worker 可直接
 * init 秒加载，避免与主线程并行下载（4.7Mbps 下双下载必然超时）。
 */
async function hasWakeModelInCache(): Promise<boolean> {
  try {
    if (!('caches' in self)) return false
    const base = import.meta.env.BASE_URL || '/'
    const cache = await caches.open('transformers-cache')
    const files = [
      'onnx/encoder_model_quantized.onnx',
      'onnx/decoder_model_merged_quantized.onnx',
    ]
    for (const f of files) {
      const url = `${self.location.origin}${base}models/onnx-community/whisper-tiny/${f}`
      const hit = await cache.match(url)
      if (!hit) return false
    }
    return true
  } catch {
    return false
  }
}

/**
 * 等待主线程预加载结束（下载的文件已写入 Cache API），供 Worker 复用缓存，避免双下载。
 * 超时 120s（F-9b）：覆盖 4.7Mbps 带宽下 110MB 模型全量下载耗时。
 */
function waitForMainThreadModel(timeoutMs = 120000): Promise<boolean> {
  return new Promise((resolve) => {
    const st = wakeModelStore.getStatus()
    if (st !== 'loading') {
      resolve(true)
      return
    }
    const start = Date.now()
    const timer = setInterval(() => {
      const s = wakeModelStore.getStatus()
      if (s !== 'loading') {
        clearInterval(timer)
        resolve(true)
      } else if (Date.now() - start >= timeoutMs) {
        clearInterval(timer)
        resolve(false)
      }
    }, 300)
  })
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
  const wakeParamsRef = useRef(resolveWakeParams())
  wakeParamsRef.current = resolveWakeParams()
  useEffect(() => { onDetectedRef.current = onDetected })
  useEffect(() => {
    pausedRef.current = paused
    if (paused) {
      dbg('检测暂停（AI 忙碌，防自听回声）')
    } else {
      dbg('检测恢复（AI 空闲，继续监听唤醒词）')
    }
  }, [paused])

  // 环境探测：仅需麦克风（AudioWorklet 不可用时自动降级 ScriptProcessor）
  useEffect(() => {
    setSupported(!!navigator.mediaDevices?.getUserMedia)
  }, [])

  useEffect(() => {
    if (!active || !supported) return undefined

    let cancelled = false
    let session: MicSessionHandle | null = null
    let worker: Worker | null = null
    let transcribeId = 0
    /** Worker 不可用时降级为主线程推理（确保唤醒功能可用，代价是推理期间短暂阻塞 UI） */
    let useMainThread = false

    // 滑窗状态
    let chunks: Float32Array[] = []
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
        dbg('转写结果:', JSON.stringify(text), '幻觉:', hallucinated, '匹配:', matched)
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
      // F-21（2026-08-10）：Worker 未就绪（F-19 预启动竞态——麦克风先启动）时跳过本窗，
      // 不置 analyzing（否则 postMessage 静默失败 + analyzing=true 卡死 → 永不转写）。
      if (!worker && !useMainThread) return
      analyzing = true
      // 降级为 debug：正常不刷屏
      dbg(`📨 提交音频窗分析 (useMainThread=${useMainThread}, 长度=${audio.length}, rms=${rms(audio).toFixed(5)})`)
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
      const { windowSamples, silenceRms } = wakeParamsRef.current
      if (totalSamples < windowSamples) return
      const merged = concatChunks(chunks, totalSamples)
      const keep = merged.slice(-KEEP_SAMPLES)
      chunks = [keep]
      totalSamples = keep.length
      if (rms(merged) < silenceRms) return
      analyzeWindow(merged)
    }

    /** 切换到主线程推理模式（Worker 失败时调用） */
    const fallbackToMainThread = (reason: string) => {
      if (useMainThread) return
      tslog('降级主线程:', reason)
      console.warn('[WakeWord] Worker 不可用，降级主线程推理:', reason)
      useMainThread = true
      worker?.terminate()
      worker = null
      wakeWorkerPromise = null // F-19：已终止的 Worker 不可复用，下次 active 重新创建
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

    // F-19（2026-08-10）：复用预启动 Worker（登录页/情绪页已 init 完成），不再 new Worker。
    // 预启动失败时降级主线程；Worker 就绪后按 F-11/F-14 同步 wakeStatus。
    tslog('useWakeWord active=true：开始获取 Worker')
    ;(async () => {
      try {
        const w = await getWakeWorker()
        tslog('getWakeWorker resolve（Worker 已就绪）')
        if (cancelled) return
        worker = w
      } catch (err) {
        if (!cancelled) {
          console.warn('[WakeWord] Worker 预启动失败:', (err as Error)?.message)
          fallbackToMainThread((err as Error)?.message || 'Worker 预启动失败')
        }
        return
      }
      if (cancelled || useMainThread || !worker) return

      worker.onmessage = (event) => {
        const { type } = event.data
        if (type === 'status') {
          const { status } = event.data
          if (status === 'loading') {
            if (wakeModelStore.getStatus() !== 'ready') setWakeStatus('loading')
          } else if (status === 'ready') {
            tslog('onmessage: Worker ready')
            setModelStatus('ready')
            // F-11（2026-08-09）：Worker ready 时同步 wakeStatus → listening。
            // F-14（2026-08-09 用户实测）：ready 后推理器仍须 ~2s 收尾/预热，
            // 延迟 2.5s 提示 standby，避免用户过早呼叫。
            setTimeout(() => {
              tslog('standby 提示（onmessage ready 路径）')
              if (!cancelled) setWakeStatus('listening')
            }, 2500)
          } else if (status === 'error') {
            fallbackToMainThread(event.data.message || 'Worker 模型加载失败')
          }
        } else if (type === 'result') {
          handleTranscribeResult(event.data.text)
        } else if (type === 'error') {
          console.warn('[WakeWord] 转写失败（忽略，下窗重试）:', event.data.message)
          analyzing = false
          maybeAnalyze()
        } else if (type === 'progress') {
          dbg(`模型加载 ${event.data.file} ${event.data.progress.toFixed(0)}%`)
        }
      }

      worker.onerror = (err) => {
        console.warn('[WakeWord] Worker 错误:', err.message)
        fallbackToMainThread(err.message || 'Worker onerror')
      }

      // F-22：getWakeWorker resolve 即 Worker 自身就绪（已不再依赖主线程状态），
      // 直接同步模型就绪 + F-14 2.5s 后提示 standby。
      tslog('Worker 复用就绪 → setModelStatus(ready)，2.5s 后 standby')
      setModelStatus('ready')
      setTimeout(() => {
        tslog('standby 提示（wakeStatus→listening）')
        if (!cancelled) setWakeStatus('listening')
      }, 2500)
    })()

    // 启动麦克风（F6：统一 micSession 模块——约束/错误映射/iOS resume 兜底/释放单点）
    ;(async () => {
      try {
        // 采样率在会话创建后取得（PCM 回调由事件循环异步触发，赋值先于首次回调，无竞态）
        let inputRate = TARGET_SAMPLE_RATE
        let chunkCount = 0
        session = await createMicSession((rawPcm: Float32Array) => {
          if (cancelled) return
          const pcm = downsampleTo16kFloat(rawPcm, inputRate)
          chunks.push(pcm)
          totalSamples += pcm.length
          chunkCount++
          // 每 50 个 chunk 打一次诊断日志（约 4s@48kHz/4096）
          if (chunkCount % 50 === 1) {
            dbg(`音频流入: chunks=${chunkCount}, totalSamples=${totalSamples}/${wakeParamsRef.current.windowSamples}, paused=${pausedRef.current}, analyzing=${analyzing}, useMainThread=${useMainThread}`)
          }
          if (totalSamples > wakeParamsRef.current.maxBufferSamples) {
            const mergedAll = concatChunks(chunks, totalSamples)
            const keepLatest = mergedAll.slice(-wakeParamsRef.current.windowSamples)
            chunks = [keepLatest]
            totalSamples = keepLatest.length
          }
          maybeAnalyze()
        })
        inputRate = session.ctx.sampleRate
        if (cancelled) {
          // unmount 时会话创建才完成：立即释放，避免麦克风泄漏
          session.stop()
          session = null
          return
        }

        // F-18（2026-08-09 最终根因）：不在此设置 listening——麦克风就绪 ≠ 转写器就绪。
        // standby（"我在这里安静地等你叫我"）只在 Worker ready（F-11）或主线程降级就绪后显示，
        // 否则用户看到 standby 呼叫时 Worker 还在等模型下载（实测：缓存空时 Worker 未启动、
        // console 无任何转写日志，呼叫 2 次无反应）。
      } catch (err) {
        console.warn('[WakeWord] 麦克风初始化失败:', (err as Error)?.message || err)
        setWakeStatus('error')
        session?.stop()
        session = null
      }
    })()

    // 释放：关闭开关 / 离开对话 / 切出待唤醒态 均走这里
    return () => {
      cancelled = true
      setWakeStatus('idle')
      session?.stop()
      session = null
      worker = null // F-19：不 terminate——转写 Worker 模块级复用（已 init），下次 active 直接复用
    }
  }, [active, supported])

  return { supported, wakeStatus }
}
