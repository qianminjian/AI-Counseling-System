/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：安卓音频路由保护
 *
 * 活跃麦克风会让 Chrome 切到"通话模式"（像打电话），把 TTS 路由到听筒且切不回扬声器。
 * 对策（对齐原 ChatRoom L187-209 语义）：
 * - 首次 pointerdown 记录 userInteracted（once 监听）
 * - 播放中 → releaseStream（保证走扬声器）
 * - 未播放且（micWanted || userInteracted）→ 600ms 后 warmUpMic（下次录音秒开）
 *   - 唤醒开启（micWanted）：挂载即预热（唤醒引擎本身也需要麦克风）
 *   - 唤醒关闭：等用户首次触摸页面后再预热（有用户手势，且用户已表达交互意图）
 */
import { useState, useEffect } from 'react'

export function useAndroidAudioRouting(opts: {
  /** 播放中（TTS 朗读） */
  playing: boolean
  /** 唤醒开启且已授权（hasConsent() && wakeEnabled）——userInteracted 联动由本 hook 内部处理 */
  micWanted: boolean
  releaseStream(): void
  warmUpMic(): void
}): void {
  const [userInteracted, setUserInteracted] = useState(false)

  useEffect(() => {
    if (userInteracted) return
    const handler = () => setUserInteracted(true)
    document.addEventListener('pointerdown', handler, { once: true })
    return () => document.removeEventListener('pointerdown', handler)
  }, [userInteracted])

  useEffect(() => {
    if (opts.playing) {
      opts.releaseStream()
    } else if (opts.micWanted || userInteracted) {
      const timer = setTimeout(() => opts.warmUpMic(), 600)
      return () => clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opts.playing, opts.micWanted, userInteracted])
}
