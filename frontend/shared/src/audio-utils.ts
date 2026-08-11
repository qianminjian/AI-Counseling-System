/**
 * 音频工具共享模块（N-011，2026-08-11）：useVoiceprint/useWakeWord 重复的
 * rms/downsample 收编——单一实现，消费点只引用不定义（对齐 emotionMeta F4 先例）。
 */

/** 均方根能量（VAD 判停用）；空数组返回 0 */
export function rms(f32: Float32Array | number[]): number {
  let sum = 0
  for (let i = 0; i < f32.length; i++) sum += f32[i] * f32[i]
  return Math.sqrt(sum / (f32.length || 1))
}

/** 线性插值降采样到目标采样率；已达标直接返回原数组 */
export function downsampleTo16k(f32: Float32Array, inputRate: number, targetRate = 16000): Float32Array {
  if (inputRate === targetRate) return f32
  const ratio = inputRate / targetRate
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
