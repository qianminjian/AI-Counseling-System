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
} from '../config/wakeWord'
import { createPcmCapture, type PcmCaptureHandle } from '../utils/createPcmCapture'

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
    transcriberPromise = (async () => {
      // 动态导入：Transformers.js 独立分包，未启用唤醒时主路径零开销
      const { pipeline, env } = await import('@huggingface/transformers')
      // 模型同源部署：从自己服务器加载（/mindsafe/models/），浏览器 HTTP 缓存持久化
      const base = import.meta.env.BASE_URL || '/'
      env.remoteHost = WAKE_MODEL_REMOTE_HOST === 'SAME_ORIGIN'
        ? `${base}models/`
        : WAKE_MODEL_REMOTE_HOST
      env.allowLocalModels = false
      // 请求持久化存储（防止浏览器在存储压力下清除模型缓存）
      navigator.storage?.persist?.().catch(() => {})
      // ONNX Runtime WASM 走本地（dist/ort/ → /mindsafe/ort/）
      const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent)
      const variant = isSafari ? 'ort-wasm-simd-threaded' : 'ort-wasm-simd-threaded.asyncify'
      env.backends.onnx.wasm.wasmPaths = {
        mjs: `${base}ort/${variant}.mjs`,
        wasm: `${base}ort/${variant}.wasm`,
      }
      return pipeline('automatic-speech-recognition', WAKE_MODEL_ID, {
        progress_callback: (p) => {
          if (p.status === 'progress' && p.file && typeof p.progress === 'number') {
            console.debug(`[WakeWord] 模型加载 ${p.file} ${p.progress.toFixed(0)}%`)
          }
        },
      })
    })().catch((err) => {
      transcriberPromise = null
      throw err
    })
  }
  return transcriberPromise
}

/**
 * 预加载模型（不启动麦克风）：在 TTS 播放期间提前下载模型，
 * 避免 TTS 播完后用户还要等模型加载。
 * 调用时机：ChatRoom 挂载 + 语音模式开启时立即调用。
 */
export function preloadWakeModel() {
  getTranscriber().catch(() => {}) // 静默失败，active 时会重试
}

/**
 * @param {object} opts
 * @param {boolean} opts.active      是否监听（仅对话内且处于待唤醒态时为 true）
 * @param {(detection: {label: string, text: string}) => void} opts.onDetected 检测到唤醒词回调
 * @returns {{ supported: boolean, wakeStatus: string }} 环境是否支持 + 当前状态（诊断用）
 */
export function useWakeWord({ active, onDetected }) {
  const [supported, setSupported] = useState(false)
  const [wakeStatus, setWakeStatus] = useState('idle') // idle | loading | listening | error
  const onDetectedRef = useRef(onDetected)
  useEffect(() => { onDetectedRef.current = onDetected })

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

    // 滑窗状态
    let chunks = []
    let totalSamples = 0
    let analyzing = false

    /** 识别一个音频窗（串行，避免积压） */
    const analyzeWindow = async (audio) => {
      analyzing = true
      try {
        const transcriber = await getTranscriber()
        if (cancelled) return
        const output = await transcriber(audio, { language: 'chinese', task: 'transcribe' })
        if (cancelled) return
        const text = output?.text || ''
        if (text) {
          console.debug('[WakeWord] 转写结果:', JSON.stringify(text))
          if (matchesWakeWord(text)) {
            console.info('[WakeWord] 🎉 检测到唤醒词:', text)
            // 立即更新状态给 UI 反馈（让用户知道“听到了，别重复了”）
            setWakeStatus('detected')
            // 延迟 300ms 再触发 onDetected，让 UI 先渲染“听到了”反馈，避免用户因无反馈而重复说唤醒词
            setTimeout(() => onDetectedRef.current?.({ label: 'halou-bobo', text }), 300)
          }
        }
      } catch (err) {
        console.warn('[WakeWord] 转写失败（忽略，下窗重试）:', err?.message || err)
      } finally {
        analyzing = false
        maybeAnalyze() // 识别期间若有新音频积满，立即处理下一窗
      }
    }

    /** 尝试提交一个滑窗（累积满 + 未在识别中 才提交） */
    const maybeAnalyze = () => {
      if (cancelled || analyzing || totalSamples < WINDOW_SAMPLES) return
      const merged = concatChunks(chunks, totalSamples)
      // 保留尾部作为下一窗前缀（避免唤醒词被窗口边界切断）
      const keep = merged.slice(-KEEP_SAMPLES)
      chunks = [keep]
      totalSamples = keep.length
      // VAD：静音窗跳过转写
      if (rms(merged) < SILENCE_RMS_THRESHOLD) return
      analyzeWindow(merged)
    }

    ;(async () => {
      // 模型加载重试（最多 3 次，间隔 5s，应对手机网络不稳定）
      const MAX_RETRIES = 3
      const RETRY_DELAY = 5000
      let lastErr: any = null

      for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        if (cancelled) return
        try {
          setWakeStatus('loading')
          console.info(`[WakeWord] 加载 Whisper 模型... (尝试 ${attempt}/${MAX_RETRIES})`)
          await getTranscriber()
          if (cancelled) return
          console.info('[WakeWord] ✅ 模型加载完成')
          lastErr = null
          break // 成功，跳出重试循环
        } catch (err) {
          lastErr = err
          console.warn(`[WakeWord] 模型加载失败 (${attempt}/${MAX_RETRIES}):`, err?.message || err)
          // getTranscriber 内部失败时已置 transcriberPromise=null，下次调用会重新加载
          if (attempt < MAX_RETRIES && !cancelled) {
            setWakeStatus('loading') // 保持 loading 状态，让用户知道在重试
            await new Promise(r => setTimeout(r, RETRY_DELAY))
          }
        }
      }

      if (cancelled) return
      if (lastErr) {
        console.warn('[WakeWord] 模型加载最终失败，等待用户手动重试')
        setWakeStatus('error')
        return
      }

      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        })
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop())
          return
        }

        audioCtx = new (window.AudioContext || (window as any).webkitAudioContext)()
        if (audioCtx.state === 'suspended') {
          await audioCtx.resume().catch(() => {})
          // iOS Safari：AudioContext 须在用户手势内 resume，异步创建可能被挂起 → 等待任意点击恢复
          if (audioCtx.state === 'suspended') {
            iosResumeHandler = () => { audioCtx?.resume().catch(() => {}) }
            document.addEventListener('pointerdown', iosResumeHandler)
          }
        }

        const inputRate = audioCtx.sampleRate
        captureHandle = await createPcmCapture(audioCtx, stream, (rawPcm: Float32Array) => {
          if (cancelled) return
          const pcm = downsampleTo16kFloat(rawPcm, inputRate)
          chunks.push(pcm)
          totalSamples += pcm.length
          // 积压上限：识别慢于采集时丢弃过老音频，只保最新一窗
          if (totalSamples > MAX_BUFFER_SAMPLES) {
            const merged = concatChunks(chunks, totalSamples)
            const keep = merged.slice(-WINDOW_SAMPLES)
            chunks = [keep]
            totalSamples = keep.length
          }
          maybeAnalyze()
        })
        console.info('[WakeWord] 音频引擎:', captureHandle.engine)

        setWakeStatus('listening')
        console.info('[WakeWord] 🎤 麦克风已启动，等待唤醒词...')
      } catch (err) {
        console.warn('[WakeWord] 麦克风初始化失败:', err?.message || err)
        setWakeStatus('error')
        stream?.getTracks().forEach((t) => t.stop())
        audioCtx?.close().catch(() => {})
      }
    })()

    // 释放：关闭开关 / 离开对话 / 切出待唤醒态 均走这里（麦克风立即释放，模型单例保留）
    return () => {
      cancelled = true
      setWakeStatus('idle')
      if (iosResumeHandler) document.removeEventListener('pointerdown', iosResumeHandler)
      captureHandle?.cleanup()
      stream?.getTracks().forEach((t) => t.stop())
      audioCtx?.close().catch(() => {})
    }
  }, [active, supported])

  return { supported, wakeStatus }
}
