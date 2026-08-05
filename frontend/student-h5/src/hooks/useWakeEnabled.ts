/**
 * useWakeEnabled —— 语音唤醒偏好收敛 hook（A4，2026-08-05）
 *
 * 背景（审计「前端状态管理分散」）：wakeEnabled 原在 ChatRoom 组件内 4 处分散管理——
 * 初始化直读 localStorage（无错误处理）、关闭/开启/授权三处重复写持久化。
 * 收敛为单一 hook：初始化 + 切换 + 持久化 + 失败安全，组件只保留消费。
 */
import { useCallback, useState } from 'react'

/** 语音唤醒开关持久化 key（design/28 §1.1） */
export const WAKE_PREF_KEY = 'mindsafe_wake_enabled'

export function useWakeEnabled() {
  // 失败安全：隐私模式/存储被禁时 getItem 抛异常 → 默认开启且不崩溃
  const [enabled, setEnabledState] = useState(() => {
    try {
      return localStorage.getItem(WAKE_PREF_KEY) !== '0'
    } catch {
      return true
    }
  })

  const setEnabled = useCallback((value: boolean) => {
    setEnabledState(value)
    try {
      localStorage.setItem(WAKE_PREF_KEY, value ? '1' : '0')
    } catch {
      // 存储不可用时状态仍在内存生效（会话内有效），持久化静默失败
    }
  }, [])

  return { enabled, setEnabled }
}
