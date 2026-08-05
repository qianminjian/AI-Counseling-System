/**
 * 动效偏好与帧率降级单测（TTSFX-004，design/37 §4.3/§4.4）
 *
 * §4.3 验收：设置项 animationEnabled 跟随系统 prefers-reduced-motion 默认值，可手动覆盖；
 *           降级模式：粒子禁用、触觉关闭、过渡改 150ms 淡入淡出。
 * §4.4 验收：掉帧检测（rAF 采样）连续 <24fps 自动切降级模式。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  createMotionPreference,
  createFpsGuard,
} from '../utils/motionPreference'

function mockMatchMedia(matches: boolean) {
  return vi.fn().mockReturnValue({ matches, addEventListener: vi.fn(), removeEventListener: vi.fn() })
}

describe('utils/motionPreference', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  describe('默认值跟随系统（§4.3）', () => {
    it('系统未开减弱动效 → 动画默认开启', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      expect(pref.animationEnabled()).toBe(true)
      expect(pref.hapticsEnabled()).toBe(true)
    })

    it('系统开启 prefers-reduced-motion → 动画默认关闭', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(true), storage: localStorage })
      expect(pref.animationEnabled()).toBe(false)
    })

    it('无 matchMedia 环境（旧浏览器）→ 安全默认开启动画', () => {
      const pref = createMotionPreference({ matchMedia: undefined, storage: localStorage })
      expect(pref.animationEnabled()).toBe(true)
    })
  })

  describe('手动覆盖与持久化（§4.3 沿用 SettingsPanel 既有模式）', () => {
    it('setAnimationEnabled 覆盖系统默认并写 localStorage', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(true), storage: localStorage })
      pref.setAnimationEnabled(true)
      expect(pref.animationEnabled()).toBe(true)

      // 新实例从 localStorage 恢复（覆盖优先于系统默认）
      const pref2 = createMotionPreference({ matchMedia: mockMatchMedia(true), storage: localStorage })
      expect(pref2.animationEnabled()).toBe(true)
    })

    it('setHapticsEnabled 独立于动画开关', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      pref.setHapticsEnabled(false)
      expect(pref.hapticsEnabled()).toBe(false)
      expect(pref.animationEnabled()).toBe(true)
    })

    it('损坏的 localStorage 值回落系统默认（失败安全）', () => {
      localStorage.setItem('bobo.animationEnabled', 'not-a-bool')
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      expect(pref.animationEnabled()).toBe(true)
    })
  })

  describe('降级模式（§4.3 降级 + §4.4 帧率自动降级）', () => {
    it('帧率降级触发后 effectiveAnimation 为 false（即使手动开启动画）', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      pref.setAnimationEnabled(true)
      pref.applyFpsDegradation()
      expect(pref.effectiveAnimationEnabled()).toBe(false)
    })

    it('降级不影响用户设置本身（恢复帧率后可回弹）', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      pref.setAnimationEnabled(true)
      pref.applyFpsDegradation()
      pref.clearFpsDegradation()
      expect(pref.effectiveAnimationEnabled()).toBe(true)
    })

    it('change 回调在任一开关/降级变化时触发', () => {
      const pref = createMotionPreference({ matchMedia: mockMatchMedia(false), storage: localStorage })
      const cb = vi.fn()
      pref.onChange(cb)
      pref.setAnimationEnabled(false)
      pref.applyFpsDegradation()
      expect(cb).toHaveBeenCalledTimes(2)
    })
  })
})

describe('utils/fpsGuard（§4.4 掉帧检测）', () => {
  let rafCallbacks: FrameRequestCallback[]
  let originalRaf: typeof requestAnimationFrame
  let originalCaf: typeof cancelAnimationFrame

  beforeEach(() => {
    rafCallbacks = []
    originalRaf = globalThis.requestAnimationFrame
    originalCaf = globalThis.cancelAnimationFrame
    globalThis.requestAnimationFrame = vi.fn((cb) => {
      rafCallbacks.push(cb)
      return rafCallbacks.length
    })
    globalThis.cancelAnimationFrame = vi.fn()
  })

  afterEach(() => {
    globalThis.requestAnimationFrame = originalRaf
    globalThis.cancelAnimationFrame = originalCaf
  })

  function pumpFrames(timestamps: number[]) {
    for (const ts of timestamps) {
      const batch = [...rafCallbacks]
      rafCallbacks = []
      for (const cb of batch) cb(ts)
    }
  }

  it('帧率正常（≥24fps）不触发降级', () => {
    const onDegrade = vi.fn()
    const guard = createFpsGuard({ threshold: 24, consecutiveSamples: 3 })
    guard.start(onDegrade)
    // 30fps = 每帧 33ms
    pumpFrames([0, 33, 66, 99, 132, 165, 198])
    expect(onDegrade).not.toHaveBeenCalled()
    guard.stop()
  })

  it('连续低帧率（<24fps）触发一次降级回调', () => {
    const onDegrade = vi.fn()
    const guard = createFpsGuard({ threshold: 24, consecutiveSamples: 3 })
    guard.start(onDegrade)
    // 12fps = 每帧 83ms，连续多帧
    pumpFrames([0, 83, 166, 249, 332, 415, 498, 581])
    expect(onDegrade).toHaveBeenCalledTimes(1) // 只触发一次，不重复
    guard.stop()
  })

  it('stop 后不再采样', () => {
    const onDegrade = vi.fn()
    const guard = createFpsGuard({ threshold: 24, consecutiveSamples: 3 })
    guard.start(onDegrade)
    guard.stop()
    expect(cancelAnimationFrame).toHaveBeenCalled()
    pumpFrames([0, 500, 1000, 1500])
    expect(onDegrade).not.toHaveBeenCalled()
  })

  it('页面隐藏/首帧无数据不误报（采样不足不判定）', () => {
    const onDegrade = vi.fn()
    const guard = createFpsGuard({ threshold: 24, consecutiveSamples: 3 })
    guard.start(onDegrade)
    pumpFrames([0, 10000]) // 单帧间隔巨大但样本不足
    expect(onDegrade).not.toHaveBeenCalled()
    guard.stop()
  })
})
