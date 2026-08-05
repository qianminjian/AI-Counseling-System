/**
 * useMotionPreference hook 单测（TTSFX-004，design/37 §4.3/§4.4）
 *
 * 契约：hook 把 motionPreference 单例接入 React 渲染——
 *   单例变更（设置切换/帧率降级）触发组件重渲染；卸载即取消订阅。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useMotionPreference, motionPref } from '../hooks/useMotionPreference'

describe('hooks/useMotionPreference', () => {
  beforeEach(() => {
    localStorage.clear()
    // 复位单例到已知态
    act(() => {
      motionPref.setAnimationEnabled(true)
      motionPref.setHapticsEnabled(true)
      motionPref.clearFpsDegradation()
    })
  })

  it('初始返回单例当前值（动画/触觉开启）', () => {
    const { result } = renderHook(() => useMotionPreference())
    expect(result.current.animationEnabled).toBe(true)
    expect(result.current.hapticsEnabled).toBe(true)
  })

  it('setAnimationEnabled(false) 持久化并触发重渲染', () => {
    const { result } = renderHook(() => useMotionPreference())
    act(() => { result.current.setAnimationEnabled(false) })
    expect(result.current.animationEnabled).toBe(false)
    expect(localStorage.getItem('bobo.animationEnabled')).toBe('false')
  })

  it('帧率降级（§4.4）压制动画：用户开着也降级', () => {
    const { result } = renderHook(() => useMotionPreference())
    act(() => { motionPref.applyFpsDegradation() })
    expect(result.current.animationEnabled).toBe(false)
    // 触觉不受帧率降级影响（§4.3 降级契约只列粒子/过渡/触觉关闭由设置控制）
    expect(result.current.hapticsEnabled).toBe(true)
    act(() => { motionPref.clearFpsDegradation() })
    expect(result.current.animationEnabled).toBe(true)
  })

  it('卸载后单例变更不再影响已卸载实例（无泄漏报错）', () => {
    const { result, unmount } = renderHook(() => useMotionPreference())
    unmount()
    expect(() => motionPref.setAnimationEnabled(false)).not.toThrow()
    expect(result.current.animationEnabled).toBe(true)
  })
})
