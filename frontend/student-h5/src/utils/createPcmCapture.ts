/**
 * 统一音频采集工具：AudioWorklet 优先，ScriptProcessorNode 降级
 *
 * 解决部分手机浏览器（QQ/UC/旧版Chrome）不支持 AudioWorkletNode 的问题。
 * ScriptProcessorNode 虽已废弃但兼容性极好（所有支持 Web Audio 的浏览器均可用）。
 *
 * 用法：
 *   const { cleanup } = await createPcmCapture(ctx, stream, (pcm: Float32Array) => { ... })
 *   // 结束时
 *   cleanup()
 */

/** AudioWorklet 处理器代码（内联 Blob，无需静态文件） */
const WORKLET_CODE = `
class PcmCaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0]?.[0]
    if (ch && ch.length > 0) this.port.postMessage(ch)
    return true
  }
}
registerProcessor('pcm-capture-processor', PcmCaptureProcessor)
`

export interface PcmCaptureHandle {
  /** 停止采集并释放节点 */
  cleanup: () => void
  /** 使用的引擎类型（诊断用） */
  engine: 'worklet' | 'script'
}

/**
 * 创建 PCM 采集管线
 * @param ctx AudioContext
 * @param stream MediaStream（麦克风）
 * @param onPcm 每次收到 Float32Array 片段时的回调
 * @returns PcmCaptureHandle
 */
export async function createPcmCapture(
  ctx: AudioContext,
  stream: MediaStream,
  onPcm: (pcm: Float32Array) => void,
): Promise<PcmCaptureHandle> {
  const source = ctx.createMediaStreamSource(stream)

  // 尝试 AudioWorklet
  if (typeof AudioWorkletNode !== 'undefined' && ctx.audioWorklet) {
    try {
      const url = URL.createObjectURL(new Blob([WORKLET_CODE], { type: 'application/javascript' }))
      try {
        await ctx.audioWorklet.addModule(url)
      } finally {
        URL.revokeObjectURL(url)
      }
      const worklet = new AudioWorkletNode(ctx, 'pcm-capture-processor')
      worklet.port.onmessage = (e) => onPcm(e.data)
      source.connect(worklet)
      // worklet 不需要连接到 destination（静默采集）
      return {
        engine: 'worklet',
        cleanup: () => {
          try { worklet.port.close() } catch { /* ignore */ }
          source.disconnect()
        },
      }
    } catch (err) {
      console.warn('[PcmCapture] AudioWorklet 失败，降级 ScriptProcessor:', (err as Error)?.message)
    }
  }

  // 降级：ScriptProcessorNode（bufferSize=4096 ≈ 85ms@48kHz，延迟可接受）
  const processor = ctx.createScriptProcessor(4096, 1, 1)
  processor.onaudioprocess = (e) => {
    const pcm = e.inputBuffer.getChannelData(0)
    // 拷贝（AudioBuffer 数据在回调结束后可能被复用）
    onPcm(new Float32Array(pcm))
  }
  source.connect(processor)
  // ScriptProcessor 必须连接到 destination 才会触发 onaudioprocess
  // 用 GainNode(0) 静音，避免回音
  const silentGain = ctx.createGain()
  silentGain.gain.value = 0
  processor.connect(silentGain)
  silentGain.connect(ctx.destination)

  return {
    engine: 'script',
    cleanup: () => {
      processor.onaudioprocess = null
      processor.disconnect()
      silentGain.disconnect()
      source.disconnect()
    },
  }
}

/**
 * 检测浏览器是否支持麦克风采集（不要求 AudioWorklet）
 * 用于 UI 判断是否显示"不支持"提示
 */
export function isMicSupported(): boolean {
  return !!navigator.mediaDevices?.getUserMedia
}
