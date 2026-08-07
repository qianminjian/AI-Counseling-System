/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：唤醒授权联动
 *
 * 挂载时（对齐原 ChatRoom L110-125 语义）：
 * - enabled 且未授权 → 800ms 后 requestConsent（首次进入自动弹出授权说明，合规 design/28 §1.4）
 * - enabled 且已授权 → onPreload（预加载唤醒模型，利用 TTS 播放时间窗口）
 * - enabled=false → 无动作
 */
import { useEffect } from 'react'

export function useWakeConsentFlow(opts: {
  enabled: boolean
  hasConsent(): boolean
  requestConsent(): boolean
  onPreload(): void
}): void {
  useEffect(() => {
    if (!opts.enabled) return
    if (!opts.hasConsent()) {
      const t = setTimeout(() => opts.requestConsent(), 800)
      return () => clearTimeout(t)
    }
    opts.onPreload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}
