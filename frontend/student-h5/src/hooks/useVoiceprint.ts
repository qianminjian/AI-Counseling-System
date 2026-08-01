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
import { getAllVoiceprints } from '../utils/voiceprintStore'

// ===== 模型单例（模块级，懒加载） =====

let extractorPromise = null

/**
 * 获取 Speaker Embedding 提取器（单例，首次调用时下载模型）
 * 使用 Transformers.js feature-extraction pipeline
 */
function getExtractor() {
  if (!extractorPromise) {
    extractorPromise = (async () => {
      const { pipeline, env } = await import('@huggingface/transformers')
      env.remoteHost = VP_MODEL_REMOTE_HOST
      env.allowLocalModels = false
      // ONNX Runtime WASM 走本地（与唤醒词共用）
      const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent)
      const variant = isSafari ? 'ort-wasm-simd-threaded' : 'ort-wasm-simd-threaded.asyncify'
      const base = import.meta.env.BASE_URL || '/'
      env.backends.onnx.wasm.wasmPaths = {
        mjs: `${base}ort/${variant}.mjs`,
        wasm: `${base}ort/${variant}.wasm`,
      }
      return pipeline('automatic-speech-recognition', VP_MODEL_ID, {
        progress_callback: (p) => {
          if (p.status === 'progress' && p.file && typeof p.progress === 'number') {
            console.debug(`[Voiceprint] 模型加载 ${p.file} ${p.progress.toFixed(0)}%`)
          }
        },
      })
    })().catch((err) => {
      extractorPromise = null
      throw err
    })
  }
  return extractorPromise
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
      const extractor = await getExtractor()

      // 超时保护
      const result = await Promise.race([
        extractor(pcm16k, { return_tensor: false }),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error('推理超时')), VP_INFERENCE_TIMEOUT)
        ),
      ])

      // Transformers.js 输出格式：{ data: Float32Array } 或 { embedding: [...] }
      const embedding = result?.data || result?.embedding || result
      if (embedding && Array.isArray(embedding) && embedding.length > 0) {
        // 归一化
        const arr = Array.from(embedding as Iterable<number>) as number[]
        const norm = Math.sqrt(arr.reduce((s, v) => s + v * v, 0))
        return norm > 0 ? arr.map((v) => v / norm) : null
      }
      return null
    } catch (err) {
      console.warn('[Voiceprint] embedding 提取失败:', err?.message || err)
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

    const matched = bestScore >= VP_VERIFY_THRESHOLD
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
    extractEmbedding,
    verify,
    checkSupport,
  }
}
