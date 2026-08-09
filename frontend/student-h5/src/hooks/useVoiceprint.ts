/**
 * 声纹识别 Hook：Speaker Embedding 提取 + 比对 + 采集状态机
 *
 * 职责：
 * - 懒加载 Speaker Embedding 模型（Transformers.js，ONNX WASM）
 * - 从音频 Float32Array 提取 256-dim embedding
 * - 余弦相似度比对（1:N 遍历所有已注册声纹）
 * - 管理采集/验证状态机
 *
 * 隐私：全程本地推理，音频不出设备，仅存特征向量。
 *
 * 降级：模型加载失败 → supported=false → UI 隐藏声纹入口，PIN 主路径不受影响。
 */
import { useState, useRef, useCallback } from 'react'
import {
  VP_MODEL_ID,
  VP_MODEL_REMOTE_HOST,
  VP_VERIFY_THRESHOLD,
  VP_SAMPLE_RATE,
  VP_SILENCE_THRESHOLD,
  VP_INFERENCE_TIMEOUT,
} from '../config/voiceprint'
import { getConfigValue } from '../config/remote'
import { getAllVoiceprints } from '../utils/voiceprintStore'
import { createModelStatusStore, type ModelStatus } from '../utils/modelStatusStore'
// DC-009：Transformers.js 初始化收敛到共享 loader（SPEC §23）
import { loadTransformersModel, createProgressHandler, formatModelError } from '../utils/transformersLoader'

// ===== 全局模型加载状态（ARCH-006 收敛 A：复用 createModelStatusStore 基座） =====

/** 声纹模型状态（类型复用 ModelStatus 基座） */
export type VpModelStatus = ModelStatus
const voiceprintModelStore = createModelStatusStore()

function setVpModelStatus(s: VpModelStatus, progress?: number, error?: string) {
  voiceprintModelStore.setStatus(s, progress, error)
}

/** 订阅声纹模型加载状态（React Hook，任意组件可调用） */
export const useVoiceprintModelStatus = voiceprintModelStore.useStatus

// ===== 模型单例（模块级，懒加载） =====

let modelBundlePromise = null

/**
 * 预加载声纹模型（不启动麦克风）：登录页挂载即调用（最早时机），
 * 避免用户点击声纹登录时还要等待模型下载。
 * 返回 Promise 供顺序编排（登录页先声纹完成再启动唤醒模型下载，避免双路抢带宽）。
 */
export function preloadVoiceprintModel(): Promise<unknown> {
  return getModelBundle().catch(() => {}) // 静默失败，实际使用时会重试
}

/**
 * 获取 WeSpeaker 模型 + FeatureExtractor（单例，首次调用时下载模型）
 *
 * 注意：WeSpeaker 是音频模型，不能用 pipeline('feature-extraction')（那是文本模型专用）。
 * 正确方式：AutoModel + AutoFeatureExtractor 分别加载，手动编排推理。
 */
function getModelBundle() {
  if (!modelBundlePromise) {
    setVpModelStatus('loading')
    modelBundlePromise = loadTransformersModel({
      modelHost: VP_MODEL_REMOTE_HOST,
      // DC-009：环境检查/env 配置已移入 loader；此处仅保留模型 API 调用与状态机
      load: async ({ AutoModel, AutoFeatureExtractor }) => {
        // WeSpeaker 是音频模型，不能用 pipeline('feature-extraction')（那是文本模型专用）。
        // 正确方式：AutoModel + AutoFeatureExtractor 分别加载，手动编排推理。
        // 并行加载模型和特征提取器——各自独立 progress_callback（避免共享 fileProgress dict 互相覆盖）
        const featureCallback = createProgressHandler((p) => {
          setVpModelStatus('loading', p)
          console.debug(`[Voiceprint] 特征提取器 ${p}%`)
        })
        const modelCallback = createProgressHandler((p) => {
          setVpModelStatus('loading', p)
          console.debug(`[Voiceprint] 模型 ${p}%`)
        })
        const [model, featureExtractor] = await Promise.all([
          AutoModel.from_pretrained(VP_MODEL_ID, {
            session_options: { graphOptimizationLevel: 'basic' as const },
            progress_callback: modelCallback,
          }),
          AutoFeatureExtractor.from_pretrained(VP_MODEL_ID, { progress_callback: featureCallback }),
        ])
        setVpModelStatus('ready', 100)
        return { model, featureExtractor }
      },
      onError: (err) => {
        const fullMsg = formatModelError(err)
        console.error('[Voiceprint] 模型加载失败（详细）:', fullMsg, err)
        setVpModelStatus('error', undefined, fullMsg)
        // 模型加载失败时清理 SW 缓存（排除旧 SW 缓存无 COOP/COEP 头的 HTML）
        if ('serviceWorker' in navigator) {
          navigator.serviceWorker.getRegistrations().then(regs => {
            regs.forEach(r => r.unregister())
          }).catch(() => {})
        }
        // BUG-CACHE-01：失败清理不得殃及 transformers-cache（唤醒模型缓存）——只删 SW 预缓存
        if ('caches' in window) {
          caches.keys().then(keys => keys.forEach(k => {
            if (!k.startsWith('transformers-cache')) caches.delete(k)
          })).catch(() => {})
        }
      },
    })
      .catch((err) => {
        modelBundlePromise = null
        // 环境不支持（SAB/SIMD）→ 静默降级，不报错；其余错误已由 onError 置 error 态
        if (err?.unsupported) {
          setVpModelStatus('unsupported')
          return null as any
        }
        throw err
      })
  }
  return modelBundlePromise
}

// ===== 数学工具 =====

/** 余弦相似度 */
function cosineSimilarity(a, b) {
  if (!a || !b || a.length !== b.length) return 0
  let dot = 0, normA = 0, normB = 0
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i]
    normA += a[i] * a[i]
    normB += b[i] * b[i]
  }
  const denom = Math.sqrt(normA) * Math.sqrt(normB)
  return denom === 0 ? 0 : dot / denom
}

/** 均方根能量（判断静音） */
function rms(f32) {
  let sum = 0
  for (let i = 0; i < f32.length; i++) sum += f32[i] * f32[i]
  return Math.sqrt(sum / (f32.length || 1))
}

/** 线性插值降采样到 16kHz */
function downsample(f32, inputRate) {
  if (inputRate === VP_SAMPLE_RATE) return f32
  const ratio = inputRate / VP_SAMPLE_RATE
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

// ===== Hook =====

/**
 * @returns {{
 *   supported: boolean,
 *   loading: boolean,
 *   extractEmbedding: (audio: Float32Array, sampleRate: number) => Promise<number[]|null>,
 *   verify: (embeddings: number[][]) => Promise<{matched: boolean, userId?: string, pseudonym?: string, score: number}>,
 *   checkSupport: () => Promise<boolean>,
 * }}
 */
export function useVoiceprint() {
  const [supported, setSupported] = useState(false)
  const [loading, setLoading] = useState(false)
  const [modelError, setModelError] = useState(false) // 模型加载失败标记
  const modelErrorRef = useRef(false) // 同步 ref，供调用方立即读取
  const initRef = useRef(false)

  /** 检测环境是否支持声纹功能 */
  const checkSupport = useCallback(async () => {
    if (initRef.current) return supported
    initRef.current = true
    // 基础环境检测（AudioWorklet 不可用时自动降级 ScriptProcessor，不再硬性要求）
    const hasMic = !!navigator.mediaDevices?.getUserMedia
    const hasIDB = typeof indexedDB !== 'undefined'
    if (!hasMic || !hasIDB) {
      setSupported(false)
      return false
    }
    setSupported(true)
    return true
  }, [supported])

  /**
   * 从音频提取 speaker embedding
   * @param {Float32Array} audio - PCM 音频
   * @param {number} sampleRate - 原始采样率
   * @returns {Promise<number[]|null>} 256-dim embedding 或 null（失败/静音）
   */
  const extractEmbedding = useCallback(async (audio, sampleRate) => {
    // 静音检测
    if (rms(audio) < VP_SILENCE_THRESHOLD) {
      return null
    }

    // 降采样到 16kHz
    const pcm16k = downsample(audio, sampleRate)

    setLoading(true)
    try {
      // 模型加载（失败 = 模型未就绪，区别于推理失败）
      let bundle
      try {
        bundle = await getModelBundle()
        setModelError(false)
        modelErrorRef.current = false
      } catch (loadErr) {
        console.warn('[Voiceprint] 模型加载失败:', loadErr?.message || loadErr)
        setModelError(true)
        modelErrorRef.current = true
        return null
      }

      const { model, featureExtractor } = bundle

      // 推理（失败 = 音频问题，非模型问题）
      const result = await Promise.race([
        (async () => {
          // WeSpeaker 正确推理流程：FeatureExtractor 提取 mel 频谱 → 模型前向 → embedding
          const { input_features } = await featureExtractor(pcm16k)
          const outputs = await model({ input_features })
          return outputs
        })(),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error('推理超时')), VP_INFERENCE_TIMEOUT)
        ),
      ])

      // WeSpeakerResNetModel 输出：{ embeddings: Tensor [1, 256] } 或 { last_hidden_state: ... }
      let embedding: number[] | null = null
      const raw: any = result

      // 优先取 embeddings 字段（WeSpeaker 专用输出）
      const tensor = raw?.embeddings ?? raw?.last_hidden_state ?? raw?.logits
      if (tensor?.dims && tensor?.data) {
        const dims = tensor.dims as number[]
        const data = tensor.data as Float32Array | number[]
        if (dims.length === 2) {
          // [batch, hidden_dim] → 取第一行
          const hiddenDim = dims[1]
          embedding = Array.from(data.slice(0, hiddenDim))
        } else if (dims.length === 3) {
          // [batch, seq_len, hidden_dim] → mean pooling
          const [, seqLen, hiddenDim] = dims
          const pooled = new Float32Array(hiddenDim)
          for (let s = 0; s < seqLen; s++) {
            for (let h = 0; h < hiddenDim; h++) {
              pooled[h] += data[s * hiddenDim + h]
            }
          }
          for (let h = 0; h < hiddenDim; h++) pooled[h] /= seqLen
          embedding = Array.from(pooled)
        } else {
          embedding = Array.from(data)
        }
      } else if (Array.isArray(raw)) {
        let arr = raw
        if (arr.length === 1 && Array.isArray(arr[0])) arr = arr[0]
        embedding = arr as number[]
      }

      if (embedding && embedding.length > 0) {
        // L2 归一化
        const norm = Math.sqrt(embedding.reduce((s, v) => s + v * v, 0))
        return norm > 0 ? embedding.map((v) => v / norm) : null
      }
      return null
    } catch (err) {
      console.warn('[Voiceprint] 推理失败:', err?.message || err)
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  /**
   * 1:N 声纹验证（遍历所有已注册声纹）
   * @param {number[][]} inputEmbeddings - 多段输入 embedding
   * @returns {Promise<{matched: boolean, userId?: string, pseudonym?: string, score: number}>}
   */
  const verify = useCallback(async (inputEmbeddings: number[][]) => {
    const allPrints = await getAllVoiceprints()
    if (allPrints.length === 0) {
      return { matched: false, score: 0 }
    }

    let bestScore = 0
    let bestMatch = null

    for (const print of allPrints) {
      // 对每个已注册声纹，计算所有输入段与所有模板段的最高相似度
      for (const inputEmb of inputEmbeddings) {
        for (const storedEmb of print.embeddings) {
          const score = cosineSimilarity(inputEmb, storedEmb)
          if (score > bestScore) {
            bestScore = score
            bestMatch = print
          }
        }
      }
    }

    const matched = bestScore >= getConfigValue('voiceprint.verifyThreshold', VP_VERIFY_THRESHOLD)
    return {
      matched,
      userId: matched ? bestMatch?.userId : undefined,
      pseudonym: matched ? bestMatch?.pseudonym : undefined,
      score: bestScore,
    }
  }, [])

  return {
    supported,
    loading,
    modelError,
    modelErrorRef,
    extractEmbedding,
    verify,
    checkSupport,
  }
}
