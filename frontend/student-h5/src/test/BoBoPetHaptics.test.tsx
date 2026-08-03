/**
 * BoBoPet 触觉反馈门禁测试（TTSFX-004，design/37 §4.3）
 *
 * 契约：设置面板关闭"触觉反馈"后，按住/松开波波不触发 navigator.vibrate。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import BoBoPet from '../components/BoBoPet'
import { motionPref } from '../hooks/useMotionPreference'

describe('BoBoPet 触觉门禁（design/37 §4.3）', () => {
  beforeEach(() => {
    localStorage.clear()
    motionPref.setHapticsEnabled(true)
  })

  it('触觉开启：pointerDown 触发 vibrate', () => {
    const vibrateSpy = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { value: vibrateSpy, writable: true, configurable: true })
    const { container } = render(<BoBoPet interactive onPointerDown={vi.fn()} />)
    fireEvent.pointerDown(container.firstElementChild!)
    expect(vibrateSpy).toHaveBeenCalled()
  })

  it('触觉关闭：pointerDown 不触发 vibrate（设置门禁）', () => {
    motionPref.setHapticsEnabled(false)
    const vibrateSpy = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { value: vibrateSpy, writable: true, configurable: true })
    const { container } = render(<BoBoPet interactive onPointerDown={vi.fn()} />)
    fireEvent.pointerDown(container.firstElementChild!)
    expect(vibrateSpy).not.toHaveBeenCalled()
  })
})
