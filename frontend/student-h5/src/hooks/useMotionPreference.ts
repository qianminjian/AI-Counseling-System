/**
 * useMotionPreference —— 动效偏好接线层（TTSFX-004，design/37 §4.3/§4.4）
 *
 * 职责单一：把 motionPreference 单例接入 React 渲染循环。
 * - 单例变更（设置切换/帧率降级）→ 触发重渲染
 * - 挂载期间运行帧率守卫（§4.4：连续 <24fps → 自动降级），卸载即停
 * - 卸载即取消订阅（无泄漏）
 */
import { useEffect, useState } from 'react'
import { createMotionPreference, createFpsGuard, type MotionPreference } from '../utils/motionPreference'

/** 隐私模式/无 localStorage 环境的内存兜底（失败安全） */
const memoryStorage: Pick<Storage, 'getItem' | 'setItem'> = (() => {
  const map = new Map<string, string>()
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => { map.set(k, v) },
  }
})()

/** 应用级单例：默认跟随系统 prefers-reduced-motion，可被设置面板手动覆盖（§4.3） */
export const motionPref: MotionPreference = createMotionPreference({
  matchMedia: typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? (q) => window.matchMedia(q)
    : undefined,
  storage: typeof localStorage !== 'undefined' ? localStorage : memoryStorage,
})

/** §4.4 帧率守卫：连续掉帧采样（低端教室平板基线 24fps，约 2.5s 连续掉帧触发降级） */
const fpsGuard = typeof requestAnimationFrame === 'function'
  ? createFpsGuard({ threshold: 24, consecutiveSamples: 60 })
  : null

export function useMotionPreference() {
  const [animationEnabled, setAnimation] = useState(() => motionPref.effectiveAnimationEnabled())
  const [hapticsEnabled, setHaptics] = useState(() => motionPref.hapticsEnabled())

  useEffect(() => {
    const unsubscribe = motionPref.onChange(() => {
      setAnimation(motionPref.effectiveAnimationEnabled())
      setHaptics(motionPref.hapticsEnabled())
    })
    fpsGuard?.start(() => motionPref.applyFpsDegradation())
    return () => {
      unsubscribe()
      fpsGuard?.stop()
    }
  }, [])

  return {
    /** 有效动画开关 = 用户设置 ∧ 帧率未降级 */
    animationEnabled,
    hapticsEnabled,
    setAnimationEnabled: (v: boolean) => motionPref.setAnimationEnabled(v),
    setHapticsEnabled: (v: boolean) => motionPref.setHapticsEnabled(v),
  }
}
