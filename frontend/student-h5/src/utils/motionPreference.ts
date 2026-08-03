/**
 * 动效偏好与帧率降级（TTSFX-004，design/37 §4.3/§4.4）
 *
 * §4.3：设置面板"动画效果/触觉反馈"开关，默认跟随系统 prefers-reduced-motion，可手动覆盖；
 *       降级模式消费方契约：Lottie/复杂动画换静态首帧、过渡改 150ms 淡入淡出、粒子禁用、触觉关闭。
 * §4.4：rAF 采样掉帧检测，连续低于 24fps 自动切降级（低端教室平板基线）。
 */

const KEY_ANIMATION = 'bobo.animationEnabled'
const KEY_HAPTICS = 'bobo.hapticsEnabled'

type MotionDeps = {
  matchMedia?: (query: string) => { matches: boolean }
  storage: Pick<Storage, 'getItem' | 'setItem'>
}

export type MotionPreference = {
  animationEnabled(): boolean
  hapticsEnabled(): boolean
  setAnimationEnabled(v: boolean): void
  setHapticsEnabled(v: boolean): void
  /** 帧率降级标记（不持久化：重启后重新采样） */
  applyFpsDegradation(): void
  clearFpsDegradation(): void
  /** 用户设置 ∧ 帧率未降级 才允许动画 */
  effectiveAnimationEnabled(): boolean
  onChange(cb: () => void): () => void
}

function readBool(storage: MotionDeps['storage'], key: string): boolean | null {
  try {
    const raw = storage.getItem(key)
    if (raw === 'true') return true
    if (raw === 'false') return false
    return null // 缺失或损坏 → 回落系统默认（失败安全）
  } catch {
    return null
  }
}

export function createMotionPreference(deps: MotionDeps): MotionPreference {
  const systemReduced = (() => {
    try {
      return deps.matchMedia ? deps.matchMedia('(prefers-reduced-motion: reduce)').matches : false
    } catch {
      return false
    }
  })()

  let animation = readBool(deps.storage, KEY_ANIMATION) ?? !systemReduced
  let haptics = readBool(deps.storage, KEY_HAPTICS) ?? !systemReduced
  let fpsDegraded = false
  const listeners = new Set<() => void>()

  const notify = () => { for (const cb of [...listeners]) { try { cb() } catch { /* 装饰层失败隔离 */ } } }

  return {
    animationEnabled: () => animation,
    hapticsEnabled: () => haptics,
    setAnimationEnabled(v) {
      animation = v
      try { deps.storage.setItem(KEY_ANIMATION, String(v)) } catch { /* 隐私模式写入失败可忽略 */ }
      notify()
    },
    setHapticsEnabled(v) {
      haptics = v
      try { deps.storage.setItem(KEY_HAPTICS, String(v)) } catch { /* 同上 */ }
      notify()
    },
    applyFpsDegradation() {
      if (!fpsDegraded) { fpsDegraded = true; notify() }
    },
    clearFpsDegradation() {
      if (fpsDegraded) { fpsDegraded = false; notify() }
    },
    effectiveAnimationEnabled() {
      return animation && !fpsDegraded
    },
    onChange(cb) {
      listeners.add(cb)
      return () => { listeners.delete(cb) }
    },
  }
}

// ===== §4.4 帧率守卫 =====

type FpsGuardOptions = {
  /** 帧率底线（fps），低于此值计为掉帧样本 */
  threshold: number
  /** 连续掉帧样本数达到即触发降级 */
  consecutiveSamples: number
}

export type FpsGuard = {
  start(onDegrade: () => void): void
  stop(): void
}

export function createFpsGuard(options: FpsGuardOptions): FpsGuard {
  const slowIntervalMs = 1000 / options.threshold
  let rafId: number | null = null
  let lastTs: number | null = null
  let slowStreak = 0
  let degraded = false
  let stopped = false

  function sample(ts: number, onDegrade: () => void) {
    if (stopped) return
    if (lastTs !== null) {
      const interval = ts - lastTs
      if (interval > slowIntervalMs) {
        slowStreak += 1
        if (slowStreak >= options.consecutiveSamples && !degraded) {
          degraded = true
          onDegrade()
        }
      } else {
        slowStreak = 0
      }
    }
    lastTs = ts
    rafId = requestAnimationFrame((t) => sample(t, onDegrade))
  }

  return {
    start(onDegrade) {
      stopped = false
      rafId = requestAnimationFrame((t) => sample(t, onDegrade))
    },
    stop() {
      stopped = true
      if (rafId !== null) {
        cancelAnimationFrame(rafId)
        rafId = null
      }
      lastTs = null
      slowStreak = 0
    },
  }
}
