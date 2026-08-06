import { useSyncExternalStore } from 'react'

/** 模型加载状态（唤醒词 / 声纹共用） */
export type ModelStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unsupported'

/** 状态快照（引用稳定，避免 useSyncExternalStore 无限循环） */
export interface ModelStatusSnapshot {
  status: ModelStatus
  progress: number // 0-100 下载进度
  error: string // 详细错误信息（诊断用）
}

/**
 * 模块级模型加载状态工厂（ARCH-006 收敛 A，design/66 §4.1）：
 * useWakeWord / useVoiceprint 原本各实现一套相同的外部 store（status/progress/error +
 * Set 订阅 + useSyncExternalStore + 快照缓存），抽取为单一基座，跨组件共享订阅。
 */
export function createModelStatusStore() {
  let status: ModelStatus = 'idle'
  let progress = 0
  let error = ''
  let snapshot: ModelStatusSnapshot = { status, progress, error }
  const subscribers = new Set<() => void>()

  /** 更新状态：progress/error 未传时保留原值；状态未变且无字段更新时跳过通知 */
  const setStatus = (s: ModelStatus, p?: number, e?: string) => {
    if (p !== undefined) progress = p
    if (e !== undefined) error = e
    if (status === s && p === undefined && e === undefined) return
    status = s
    // 更新缓存快照（保证引用稳定，避免 useSyncExternalStore 无限循环）
    snapshot = { status, progress, error }
    subscribers.forEach((fn) => fn())
  }

  /** React Hook：订阅模型加载状态（任意组件可调用） */
  const useStatus = (): ModelStatusSnapshot => useSyncExternalStore(
    (cb) => { subscribers.add(cb); return () => { subscribers.delete(cb) } },
    () => snapshot,
  )

  /** 非 React 环境读取当前状态（如 console 调试） */
  const getStatus = (): ModelStatus => status

  /** @internal 测试专用：重置单例状态，避免跨测试污染 */
  const reset = () => {
    status = 'idle'
    progress = 0
    error = ''
    snapshot = { status, progress, error }
  }

  return { setStatus, useStatus, getStatus, reset }
}
