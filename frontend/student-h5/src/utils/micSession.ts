/**
 * 统一麦克风采集会话（F6，doing/78 §13）
 *
 * 三处各自演化的麦克风启动/释放语义收敛为单一模块：
 * - VoiceLoginOverlay.initMic：getUserMedia + AudioContext + resume + 音量 rms
 * - useWakeWord 启动段：getUserMedia + AudioContext + resume + iOS pointerdown 兜底 + PCM 缓冲
 * - useAudioRecorder.warmUp：第三种流管理（MediaRecorder 场景）
 *
 * 统一契约：
 * - 采集约束单点（MIC_CONSTRAINTS：单声道 + 回声消除/降噪/自动增益）
 * - 错误映射（mapMicError：权限拒绝/设备缺失/安全上下文）
 * - AudioContext 创建 + resume（含 iOS suspended 时 pointerdown 手势兜底）
 * - PCM 挂载（createPcmCapture，AudioWorklet 优先/ScriptProcessor 降级）
 * - 释放单点（session.stop()：capture + 麦克风 + AudioContext + 手势兜底一并清理）
 *
 * 用法（PCM 采集会话）：
 *   const session = await createMicSession((pcm) => { ... })  // 失败抛 MicError
 *   const sampleRate = session.ctx.sampleRate
 *   session.stop()   // 释放全部资源
 * 用法（仅流预热，MediaRecorder 场景）：
 *   const stream = await getMicStream()
 */
import { createPcmCapture, type PcmCaptureHandle } from './createPcmCapture'

/** 统一采集约束（单声道利于 VAD/声纹；回声消除/降噪/自动增益提升识别质量） */
export const MIC_CONSTRAINTS: MediaTrackConstraints = {
  channelCount: 1,
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
}

export type MicErrorKind = 'not-allowed' | 'not-found' | 'security' | 'unknown'

export interface MicError extends Error {
  kind: MicErrorKind
}

/** 麦克风错误映射（权限生命周期成为可测契约：拒绝/设备缺失/非安全上下文） */
export function mapMicError(err: unknown): MicError {
  const name = (err as DOMException)?.name || (err as Error)?.name || ''
  const kind: MicErrorKind =
    name === 'NotAllowedError' || name === 'PermissionDeniedError' ? 'not-allowed'
      : name === 'NotFoundError' || name === 'DevicesNotFoundError' ? 'not-found'
        : name === 'SecurityError' ? 'security'
          : 'unknown'
  const mapped = new Error((err as Error)?.message || `麦克风不可用（${name}）`) as MicError
  mapped.kind = kind
  return mapped
}

/** 获取麦克风流（统一约束 + 错误映射；失败抛 MicError） */
export async function getMicStream(): Promise<MediaStream> {
  try {
    return await navigator.mediaDevices.getUserMedia({ audio: MIC_CONSTRAINTS })
  } catch (err) {
    throw mapMicError(err)
  }
}

export interface MicSessionHandle {
  /** 释放全部资源（幂等）：PCM 采集节点 + 麦克风轨道 + AudioContext + iOS 手势兜底 */
  stop: () => void
  ctx: AudioContext
  stream: MediaStream
  /** 使用的 PCM 采集引擎（诊断用） */
  engine: 'worklet' | 'script'
}

/**
 * 创建麦克风 PCM 采集会话
 * @param onPcm 每次收到 Float32Array 片段时的回调（音量动画/VAD/缓冲均由调用方处理）
 * @returns MicSessionHandle；失败抛 MicError（调用方负责错误态映射）
 */
export async function createMicSession(onPcm: (pcm: Float32Array) => void): Promise<MicSessionHandle> {
  const stream = await getMicStream()
  const ctx = new (window.AudioContext || window.webkitAudioContext)()
  if (ctx.state === 'suspended') {
    await ctx.resume().catch(() => {})
  }
  // iOS Safari：用户手势可恢复被中断的 AudioContext（如来电/息屏后恢复）
  let iosResumeHandler: (() => void) | null = null
  if (ctx.state === 'suspended') {
    iosResumeHandler = () => { ctx.resume().catch(() => {}) }
    document.addEventListener('pointerdown', iosResumeHandler)
  }

  let capture: PcmCaptureHandle | null = null
  try {
    capture = await createPcmCapture(ctx, stream, onPcm)
  } catch (err) {
    // 挂载失败也要释放已获取的流与上下文
    if (iosResumeHandler) document.removeEventListener('pointerdown', iosResumeHandler)
    stream.getTracks().forEach((t) => t.stop())
    ctx.close().catch(() => {})
    throw err
  }

  return {
    stop: () => {
      capture?.cleanup()
      if (iosResumeHandler) document.removeEventListener('pointerdown', iosResumeHandler)
      stream.getTracks().forEach((t) => t.stop())
      ctx.close().catch(() => {})
    },
    ctx,
    stream,
    engine: capture.engine,
  }
}
