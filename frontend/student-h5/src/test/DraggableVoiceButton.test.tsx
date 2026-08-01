import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, act } from '@testing-library/react'
import DraggableVoiceButton from '../components/DraggableVoiceButton'

/** 在 jsdom 中模拟 PointerEvent（jsdom 不原生支持 pointerId） */
function firePointer(el: Element, type: string, props: { clientX?: number; clientY?: number; pointerId?: number }) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'pointerId', { value: props.pointerId ?? 1 })
  Object.defineProperty(event, 'clientX', { value: props.clientX ?? 0 })
  Object.defineProperty(event, 'clientY', { value: props.clientY ?? 0 })
  // React 需要 currentTarget 上的 setPointerCapture
  act(() => { el.dispatchEvent(event) })
}

describe('DraggableVoiceButton', () => {
  beforeEach(() => {
    localStorage.clear()
    // jsdom 默认 innerWidth=1024, innerHeight=768
    Object.defineProperty(window, 'innerWidth', { value: 375, writable: true })
    Object.defineProperty(window, 'innerHeight', { value: 667, writable: true })
    // jsdom 不实现 PointerEvent capture API
    Element.prototype.setPointerCapture = vi.fn()
    Element.prototype.releasePointerCapture = vi.fn()
  })

  const defaultProps = {
    onPointerDown: vi.fn(),
    onPointerMove: vi.fn(),
    onPointerUp: vi.fn(),
    onPointerCancel: vi.fn(),
  }

  it('渲染 children 内容', () => {
    const { getByText } = render(
      <DraggableVoiceButton {...defaultProps}>
        <span>波波</span>
      </DraggableVoiceButton>
    )
    expect(getByText('波波')).toBeTruthy()
  })

  it('children 为函数时传入 side 参数', () => {
    const childFn = vi.fn((side) => <span>{side}</span>)
    render(<DraggableVoiceButton {...defaultProps}>{childFn}</DraggableVoiceButton>)
    expect(childFn).toHaveBeenCalledWith(expect.stringMatching(/^(left|right)$/))
  })

  it('初始位置在安全区内', () => {
    const { container } = render(
      <DraggableVoiceButton {...defaultProps}><span>test</span></DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement
    const left = parseInt(el.style.left)
    const top = parseInt(el.style.top)
    // 安全区：x >= 12, y >= 80
    expect(left).toBeGreaterThanOrEqual(12)
    expect(top).toBeGreaterThanOrEqual(80)
    // 不超出右/下边界
    expect(left).toBeLessThanOrEqual(375 - 72 - 12)
    expect(top).toBeLessThanOrEqual(667 - 72 - 92)
  })

  it('从 localStorage 恢复位置', () => {
    localStorage.setItem('mindsafe_voice_btn_pos_v2', JSON.stringify({ x: 50, y: 200 }))
    const { container } = render(
      <DraggableVoiceButton {...defaultProps}><span>test</span></DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement
    expect(parseInt(el.style.left)).toBe(50)
    expect(parseInt(el.style.top)).toBe(200)
  })

  it('短按（位移<10px）透传 onPointerDown 和 onPointerUp', () => {
    const onPointerDown = vi.fn()
    const onPointerUp = vi.fn()
    const { container } = render(
      <DraggableVoiceButton {...defaultProps} onPointerDown={onPointerDown} onPointerUp={onPointerUp}>
        <span>test</span>
      </DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement

    firePointer(el, 'pointerdown', { clientX: 100, clientY: 300, pointerId: 1 })
    expect(onPointerDown).toHaveBeenCalledTimes(1)

    // 微小移动（<10px）
    firePointer(el, 'pointermove', { clientX: 103, clientY: 302, pointerId: 1 })
    // 松手 → 透传 up
    firePointer(el, 'pointerup', { clientX: 103, clientY: 302, pointerId: 1 })
    expect(onPointerUp).toHaveBeenCalledTimes(1)
  })

  it('拖拽（位移≥10px）取消录音并移动按钮', () => {
    const onPointerDown = vi.fn()
    const onPointerCancel = vi.fn()
    const onPointerUp = vi.fn()
    const { container } = render(
      <DraggableVoiceButton
        {...defaultProps}
        onPointerDown={onPointerDown}
        onPointerCancel={onPointerCancel}
        onPointerUp={onPointerUp}
      >
        <span>test</span>
      </DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement

    firePointer(el, 'pointerdown', { clientX: 100, clientY: 300, pointerId: 1 })
    expect(onPointerDown).toHaveBeenCalledTimes(1)

    // 大幅移动（≥10px）→ 判定为拖拽，取消录音
    firePointer(el, 'pointermove', { clientX: 130, clientY: 330, pointerId: 1 })
    expect(onPointerCancel).toHaveBeenCalledTimes(1)

    // 松手 → 不透传 up（因为是拖拽）
    firePointer(el, 'pointerup', { clientX: 130, clientY: 330, pointerId: 1 })
    expect(onPointerUp).not.toHaveBeenCalled()
  })

  it('拖拽后位置持久化到 localStorage', () => {
    const { container } = render(
      <DraggableVoiceButton {...defaultProps}><span>test</span></DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement

    firePointer(el, 'pointerdown', { clientX: 100, clientY: 300, pointerId: 1 })
    firePointer(el, 'pointermove', { clientX: 150, clientY: 350, pointerId: 1 })
    firePointer(el, 'pointerup', { clientX: 150, clientY: 350, pointerId: 1 })

    const saved = JSON.parse(localStorage.getItem('mindsafe_voice_btn_pos_v2') || '{}')
    expect(saved).toHaveProperty('x')
    expect(saved).toHaveProperty('y')
  })

  it('disabled 时不响应 pointerDown', () => {
    const onPointerDown = vi.fn()
    const { container } = render(
      <DraggableVoiceButton {...defaultProps} onPointerDown={onPointerDown} disabled>
        <span>test</span>
      </DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement
    fireEvent.pointerDown(el, { clientX: 100, clientY: 300, pointerId: 1 })
    expect(onPointerDown).not.toHaveBeenCalled()
  })

  it('按钮尺寸为 72px', () => {
    const { container } = render(
      <DraggableVoiceButton {...defaultProps}><span>test</span></DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement
    expect(el.style.width).toBe('72px')
    expect(el.style.height).toBe('72px')
  })

  it('拖拽时添加 scale-110 class', () => {
    const { container } = render(
      <DraggableVoiceButton {...defaultProps}><span>test</span></DraggableVoiceButton>
    )
    const el = container.firstElementChild as HTMLElement

    firePointer(el, 'pointerdown', { clientX: 100, clientY: 300, pointerId: 1 })
    firePointer(el, 'pointermove', { clientX: 130, clientY: 330, pointerId: 1 })
    expect(el.className).toContain('scale-110')
  })
})
