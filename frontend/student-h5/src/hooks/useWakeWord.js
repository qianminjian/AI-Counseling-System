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

/** Whisper 要求的采样率 */
const TARGET_SAMPLE_RATE = 16000

/** 滑窗/重叠/积压上限（样本数 @16kHz） */
const WINDOW_SAMPLES = Math.round(WAKE_WINDOW_SECONDS * TARGET_SAMPLE_RATE)
const KEEP_SAMPLES = Math.round(WAKE_KEEP_SECONDS * TARGET_SAMPLE_RATE)
const MAX_BUFFER_SAMPLES = WINDOW_SAMPLES * 2

/** AudioWorklet 处理器：把麦克风 PCM（Float32 单声道）转发到主线程（内联 Blob，无需静态文件） */
const CAPTURE_WORKLET_CODE = `
class WakeCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (input && input[0] && input[0].length > 0) {
      this.port.postMessage(input[0])
    }
    return true
  }
}
registerProcessor('wake-capture-processor', WakeCaptureProcessor)
`

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
      env.remoteHost = WAKE_MODEL_REMOTE_HOST
      env.allowLocalModels = false
      // ONNX Runtime WASM 走本地（dist/ort/ → /mindsafe/ort/）
      const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent)
      const variant = isSafari ? 'ort-wasm-simd-threaded' : 'ort-wasm-simd-threaded.asyncify'
      const base = import.meta.env.BASE_URL || '/'
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
 * @param {object} opts
 * @param {boolean} opts.active      是否监听（仅对话内且处于待唤醒态时为 true）
 * @param {(detection: {label: string, text: string}) => void} opts.onDetected 检测到唤醒词回调
 * @returns {{ supported: boolean }} 环境是否支持语音唤醒（false 时 UI 应隐藏开关）
 */
export function useWakeWord({ active, onDetected }) {
  const [supported, setSupported] = useState(false)
  const onDetectedRef = useRef(onDetected)
  useEffect(() => { onDetectedRef.current = onDetected })

  // 环境探测：麦克风 + AudioWorklet（无需任何外部账号/配置）
  useEffect(() => {
    setSupported(
      !!navigator.mediaDevices?.getUserMedia &&
      typeof AudioWorkletNode !== 'undefined'
    )
  }, [])

  useEffect(() => {
    if (!active || !supported) return undefined

    let cancelled = false
    let stream = null
    let audioCtx = null
    let sourceNode = null
    let workletNode = null
    let iosResumeHandler = null

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
        if (text && matchesWakeWord(text)) {
          onDetectedRef.current?.({ label: 'halou-bobo', text })
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
      try {
        // 预加载模型（首次约 20MB 下载，之后浏览器缓存）
        await getTranscriber()
        if (cancelled) return

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
          // iOS Safari：AudioContext 须在用户手势内 resume，异步创建可能被挂起 → 等待任意点击恢复
          if (audioCtx.state === 'suspended') {
            iosResumeHandler = () => { audioCtx?.resume().catch(() => {}) }
            document.addEventListener('pointerdown', iosResumeHandler)
          }
        }

        const workletUrl = URL.createObjectURL(new Blob([CAPTURE_WORKLET_CODE], { type: 'application/javascript' }))
        try {
          await audioCtx.audioWorklet.addModule(workletUrl)
        } finally {
          URL.revokeObjectURL(workletUrl)
        }

        sourceNode = audioCtx.createMediaStreamSource(stream)
        workletNode = new AudioWorkletNode(audioCtx, 'wake-capture-processor')

        const inputRate = audioCtx.sampleRate
        workletNode.port.onmessage = (e) => {
          if (cancelled) return
          const pcm = downsampleTo16kFloat(e.data, inputRate)
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
        }

        sourceNode.connect(workletNode)
        // 不连接 destination：只采集不回放（避免反馈啸叫）
      } catch (err) {
        console.warn('[WakeWord] 初始化失败（下次激活时重试）:', err?.message || err)
        // 不再 setSupported(false)：保持 UI 开关可见，允许用户重试
        // 清理已获取的部分资源
        stream?.getTracks().forEach((t) => t.stop())
        audioCtx?.close().catch(() => {})
      }
    })()

    // 释放：关闭开关 / 离开对话 / 切出待唤醒态 均走这里（麦克风立即释放，模型单例保留）
    return () => {
      cancelled = true
      if (iosResumeHandler) document.removeEventListener('pointerdown', iosResumeHandler)
      try { workletNode?.port.close() } catch { /* ignore */ }
      try { sourceNode?.disconnect() } catch { /* ignore */ }
      stream?.getTracks().forEach((t) => t.stop())
      audioCtx?.close().catch(() => {})
    }
  }, [active, supported])

  return { supported }
}
