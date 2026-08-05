/**
 * useWakeEnabled hook 单测（A4，2026-08-05：收敛 ChatRoom 分散的唤醒偏好状态管理）
 *
 * 背景（审计「前端状态管理分散」）：wakeEnabled 原在 ChatRoom 组件内 4 处分散管理——
 *   1) useState 惰性初始化直读 localStorage（无错误处理）
 *   2) handleToggleWake 关闭分支写 '0'
 *   3) handleToggleWake 开启分支写 '1'
 *   4) handleWakeConsentGrant 授权回调再写 '1'
 * 同一状态的初始化/持久化逻辑散落组件，且 localStorage 异常会整组件崩溃。
 * 契约：hook 收敛「初始化 + 切换 + 持久化 + 失败安全」，组件只保留消费。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWakeEnabled, WAKE_PREF_KEY } from '../hooks/useWakeEnabled'

describe('hooks/useWakeEnabled', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('无持久化记录 → 默认开启（现有行为：getItem !== "0"）', () => {
    const { result } = renderHook(() => useWakeEnabled())
    expect(result.current.enabled).toBe(true)
  })

  it('持久化记录 "0" → 初始化关闭', () => {
    localStorage.setItem(WAKE_PREF_KEY, '0')
    const { result } = renderHook(() => useWakeEnabled())
    expect(result.current.enabled).toBe(false)
  })

  it('setEnabled(false) → 状态更新 + localStorage 持久化 "0"', () => {
    const { result } = renderHook(() => useWakeEnabled())
    act(() => { result.current.setEnabled(false) })
    expect(result.current.enabled).toBe(false)
    expect(localStorage.getItem(WAKE_PREF_KEY)).toBe('0')
  })

  it('setEnabled(true) → 状态更新 + localStorage 持久化 "1"', () => {
    localStorage.setItem(WAKE_PREF_KEY, '0')
    const { result } = renderHook(() => useWakeEnabled())
    act(() => { result.current.setEnabled(true) })
    expect(result.current.enabled).toBe(true)
    expect(localStorage.getItem(WAKE_PREF_KEY)).toBe('1')
  })

  it('localStorage getItem 异常（隐私模式/存储被禁）→ 失败安全，返回默认开启且不崩溃', () => {
    const originalGetItem = Storage.prototype.getItem
    Storage.prototype.getItem = () => { throw new Error('SecurityError: access denied') }
    try {
      const { result } = renderHook(() => useWakeEnabled())
      expect(result.current.enabled).toBe(true)
    } finally {
      Storage.prototype.getItem = originalGetItem
    }
  })
})
