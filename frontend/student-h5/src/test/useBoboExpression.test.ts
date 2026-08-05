/**
 * useBoboExpression hook 单测（TTSFX-004，design/37 §4.1/§三.1）
 *
 * 契约：hook 是 emotionBus 与表情状态机 reducer 的接线层——
 *   emotionBus 发布情绪 → 表情随之切换；交互事件（打字/思考/风险）经 dispatch 进入。
 *   S0/S1 锁定期间 emotionBus 事件不得改变表情（安全红线）。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useBoboExpression } from '../hooks/useBoboExpression'
import { emotionBus } from '../utils/emotionBus'

describe('hooks/useBoboExpression', () => {
  beforeEach(() => {
    emotionBus.reset()
  })

  it('初始状态 idle 未锁定', () => {
    const { result } = renderHook(() => useBoboExpression())
    expect(result.current.expression).toBe('idle')
    expect(result.current.locked).toBe(false)
  })

  it('emotionBus.publish(soothe) → hug（三方同源契约）', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { emotionBus.publish('soothe') })
    expect(result.current.expression).toBe('hug')
  })

  it('emotionBus.publish(encourage) → cheer', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { emotionBus.publish('encourage') })
    expect(result.current.expression).toBe('cheer')
  })

  it('未知情绪标签 → 回落 idle', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { emotionBus.publish('angry') })
    expect(result.current.expression).toBe('idle')
  })

  it('dispatch typing/thinking 切换 listen/think', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { result.current.dispatch({ type: 'typing' }) })
    expect(result.current.expression).toBe('listen')
    act(() => { result.current.dispatch({ type: 'thinking' }) })
    expect(result.current.expression).toBe('think')
  })

  it('risk ≥2 锁定 hug：后续 emotionBus 事件不得改变表情（S0/S1 红线）', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { result.current.dispatch({ type: 'risk', riskLevel: 3 }) })
    expect(result.current.expression).toBe('hug')
    expect(result.current.locked).toBe(true)
    act(() => { emotionBus.publish('happy') })
    expect(result.current.expression).toBe('hug')
    act(() => { result.current.dispatch({ type: 'typing' }) })
    expect(result.current.expression).toBe('hug')
  })

  it('risk-cleared 解锁后恢复响应', () => {
    const { result } = renderHook(() => useBoboExpression())
    act(() => { result.current.dispatch({ type: 'risk', riskLevel: 2 }) })
    act(() => { result.current.dispatch({ type: 'risk-cleared' }) })
    expect(result.current.locked).toBe(false)
    act(() => { emotionBus.publish('happy') })
    expect(result.current.expression).toBe('happy')
  })

  it('卸载后取消订阅：emotionBus 发布不再影响已卸载实例（无泄漏报错）', () => {
    const { result, unmount } = renderHook(() => useBoboExpression())
    unmount()
    expect(() => emotionBus.publish('happy')).not.toThrow()
    // 值停留在卸载前（不再更新）
    expect(result.current.expression).toBe('idle')
  })
})
