import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import BoBoPet from '../components/BoBoPet'

describe('BoBoPet', () => {
  it('默认 idle 状态渲染海豚 SVG', () => {
    const { container } = render(<BoBoPet />)
    expect(container.querySelector('svg')).toBeTruthy()
    expect(container.querySelector('svg viewBox')).toBeFalsy()
    // 有眼睛、身体路径
    expect(container.querySelectorAll('circle').length).toBeGreaterThanOrEqual(2)
  })

  it('aria-label 默认"波波"', () => {
    render(<BoBoPet />)
    expect(screen.getByLabelText('波波')).toBeTruthy()
  })

  it('interactive 模式：role=button + aria-label"按住波波说话"', () => {
    render(<BoBoPet interactive />)
    const el = screen.getByRole('button')
    expect(el.getAttribute('aria-label')).toBe('按住波波说话')
  })

  it('interactive 模式：pointerDown 触发回调 + vibrate', () => {
    const onPointerDown = vi.fn()
    const vibrateSpy = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { value: vibrateSpy, writable: true, configurable: true })

    const { container } = render(<BoBoPet interactive onPointerDown={onPointerDown} />)
    const el = container.firstElementChild!
    fireEvent.pointerDown(el)
    expect(onPointerDown).toHaveBeenCalledTimes(1)
    expect(vibrateSpy).toHaveBeenCalledWith(10)
  })

  it('disabled 时 interactive 不触发 pointerDown', () => {
    const onPointerDown = vi.fn()
    const { container } = render(<BoBoPet interactive disabled onPointerDown={onPointerDown} />)
    fireEvent.pointerDown(container.firstElementChild!)
    expect(onPointerDown).not.toHaveBeenCalled()
  })

  it('listening 状态：渲染圆球形态（声波纹）', () => {
    const { container } = render(<BoBoPet state="listening" liveTranscript="你好" />)
    // 圆球形态有 rounded-full 球体
    const ball = container.querySelector('.rounded-full')
    expect(ball).toBeTruthy()
    // 声波纹
    expect(container.querySelectorAll('.animate-\\[bobo-ripple_1\\.5s_ease-out_infinite\\]').length).toBe(2)
  })

  it('cancelArmed 时圆球变红', () => {
    const { container } = render(<BoBoPet state="listening" cancelArmed />)
    // 找到带 radial-gradient 样式的圆球 div
    const divs = container.querySelectorAll('div.rounded-full')
    const ball = Array.from(divs).find(d => d.getAttribute('style')?.includes('radial-gradient'))
    expect(ball?.getAttribute('style')).toContain('#EF4444')
  })

  it('speaking 状态：嘴巴为椭圆（张开）', () => {
    const { container } = render(<BoBoPet state="speaking" sentenceText="你好呀" />)
    // speaking 时嘴巴是 ellipse（非 path 曲线）
    const ellipses = container.querySelectorAll('ellipse')
    const mouth = Array.from(ellipses).find(e => e.getAttribute('cx') === '167')
    expect(mouth).toBeTruthy()
  })

  it('非 speaking 状态：嘴巴为微笑曲线', () => {
    const { container } = render(<BoBoPet state="idle" />)
    // path 曲线嘴巴
    const paths = container.querySelectorAll('path')
    const smilePath = Array.from(paths).find(p => p.getAttribute('d')?.includes('M 176 76'))
    expect(smilePath).toBeTruthy()
  })

  it('waitingWake 状态：渲染光晕', () => {
    const { container } = render(<BoBoPet state="waitingWake" />)
    const halo = container.querySelector('.pointer-events-none')
    expect(halo).toBeTruthy()
    expect(halo?.getAttribute('style')).toContain('bobo-halo-slow')
  })

  it('interactive + idle 渲染呼吸光晕', () => {
    const { container } = render(<BoBoPet interactive state="idle" />)
    const halo = container.querySelector('.pointer-events-none')
    expect(halo).toBeTruthy()
    expect(halo?.getAttribute('style')).toContain('bobo-halo')
  })

  it('自定义 size 生效', () => {
    const { container } = render(<BoBoPet size={120} />)
    const el = container.firstElementChild as HTMLElement
    expect(el.style.width).toBe('120px')
    expect(el.style.height).toBe('120px')
  })

  it('自定义 colors 传入 SVG fill', () => {
    const colors = { body: '#FF0000', belly: '#00FF00', fin: '#0000FF' }
    const { container } = render(<BoBoPet colors={colors} />)
    const bodyPath = container.querySelector(`path[fill="${colors.body}"]`)
    expect(bodyPath).toBeTruthy()
  })

  it('包含 keyframes style 标签', () => {
    const { container } = render(<BoBoPet />)
    const style = container.querySelector('style')
    expect(style?.textContent).toContain('@keyframes bobo-float')
    expect(style?.textContent).toContain('@keyframes bobo-pulse')
  })

  it('interactive 模式：pointerUp 触发回调 + vibrate', () => {
    const onPointerUp = vi.fn()
    const vibrateSpy = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { value: vibrateSpy, writable: true, configurable: true })
    const { container } = render(<BoBoPet interactive onPointerUp={onPointerUp} />)
    fireEvent.pointerUp(container.firstElementChild!)
    expect(onPointerUp).toHaveBeenCalledTimes(1)
    expect(vibrateSpy).toHaveBeenCalledWith(10)
  })

  it('interactive 模式：pointerCancel 触发回调 + 震动模式', () => {
    const onPointerCancel = vi.fn()
    const vibrateSpy = vi.fn()
    Object.defineProperty(navigator, 'vibrate', { value: vibrateSpy, writable: true, configurable: true })
    const { container } = render(<BoBoPet interactive onPointerCancel={onPointerCancel} />)
    fireEvent.pointerCancel(container.firstElementChild!)
    expect(onPointerCancel).toHaveBeenCalledTimes(1)
    expect(vibrateSpy).toHaveBeenCalledWith([20, 30, 20])
  })

  it('disabled 时 pointerUp 不触发回调', () => {
    const onPointerUp = vi.fn()
    const { container } = render(<BoBoPet interactive disabled onPointerUp={onPointerUp} />)
    fireEvent.pointerUp(container.firstElementChild!)
    expect(onPointerUp).not.toHaveBeenCalled()
  })
})
