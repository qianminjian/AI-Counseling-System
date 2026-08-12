import { notification } from 'antd'

/**
 * 教师端预警触达工具（板块08 P2-2：从 Dashboard.tsx 页内移出，独立可测试）
 *
 * playAlertSound / sendDesktopNotification 原与组件耦合（模块级变量 alertAudioCtx
 * 也在组件文件内），无法独立测试。移入本模块后：
 * - 组件只 import 消费，行为零变化（useAlertWebSocket 的 onAlert 回调与 15s 轮询共用）
 * - AudioContext 单例生命周期收敛在本模块（F-10：高频触发不重复 new / 不泄漏）
 */

// F-10：AudioContext 单例复用（预警高频触发时避免每次 new + 不 close 的泄漏）
let alertAudioCtx: AudioContext | null = null

/** 预警提示音（880Hz 短促正弦渐弱；音频不可用环境静默降级） */
export function playAlertSound(): void {
  try {
    const Ctor = window.AudioContext || (window as any).webkitAudioContext
    if (!alertAudioCtx) {
      alertAudioCtx = new Ctor()
    }
    const ctx = alertAudioCtx
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.value = 880
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5)
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + 0.5)
  } catch { /* 音频不可用环境静默降级 */ }
}

/** 桌面通知：已授权走 Notification，不可用/未授权降级为页内通知（不静默丢弃） */
export function sendDesktopNotification(title: string, body: string): void {
  // 桌面通知不可用或未授权时，降级为页内通知（不静默丢弃）
  if ('Notification' in window && Notification.permission === 'granted') {
    try {
      new Notification(title, { body, icon: '🛡️' })
      return
    } catch { /* 部分移动浏览器构造器不可用，落入页内通知 */ }
  }
  notification.warning({ message: title, description: body, placement: 'topRight', duration: 6 })
}
