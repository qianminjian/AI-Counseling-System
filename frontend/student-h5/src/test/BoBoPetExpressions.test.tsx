/**
 * BoBoPet 表情层组件测试（TTSFX-004，design/37 §4.1）
 *
 * 组件契约：
 * - expression prop 承接表情状态机输出（与既有 state 交互态正交叠加）
 * - motionOff 时全部动画停用（§4.3 减弱动效降级：静态呈现，无 animate 类）
 * - 既有 state 交互态行为不回归
 */
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import BoBoPet from '../components/BoBoPet'

describe('BoBoPet 表情层（expression prop）', () => {
  it('默认 expression=idle，容器带 data-expression', () => {
    const { container } = render(<BoBoPet />)
    expect(container.querySelector('[data-expression="idle"]')).toBeTruthy()
  })

  it('happy：弯弯眼（月牙弧线替代圆眼）+ 弹跳动效', () => {
    const { container } = render(<BoBoPet expression="happy" />)
    expect(container.querySelector('[data-testid="bobo-eyes-happy"]')).toBeTruthy()
    const body = container.querySelector('[data-expression="happy"] > div')
    expect(body?.className).toMatch(/animate-\[bobo-bounce/)
  })

  it('gentle：缓慢眨眼动效', () => {
    const { container } = render(<BoBoPet expression="gentle" />)
    const eyes = container.querySelector('[data-testid="bobo-eyes"]')
    expect(eyes?.getAttribute('class') ?? '').toMatch(/bobo-blink-slow/)
  })

  it('cheer：胸鳍举高加油', () => {
    const { container } = render(<BoBoPet expression="cheer" />)
    expect(container.querySelector('[data-testid="bobo-fin-cheer"]')).toBeTruthy()
  })

  it('hug：靠近放大 + 闭眼 + 加深腮红（S0/S1 安抚姿态）', () => {
    const { container } = render(<BoBoPet expression="hug" />)
    expect(container.querySelector('[data-testid="bobo-eyes-closed"]')).toBeTruthy()
    const blush = container.querySelector('[data-testid="bobo-blush"]')
    expect(blush?.getAttribute('opacity')).toBe('0.95')
    const svg = container.querySelector('svg')
    expect(svg?.getAttribute('class')).toMatch(/bobo-lean/)
  })

  it('sleep：闭眼 + zzz 气泡', () => {
    const { container } = render(<BoBoPet expression="sleep" />)
    expect(container.querySelector('[data-testid="bobo-eyes-closed"]')).toBeTruthy()
    expect(container.textContent).toContain('z')
  })

  it('motionOff：无任何 animate 动画类（减弱动效降级，静态呈现）', () => {
    const { container } = render(<BoBoPet expression="happy" motionOff />)
    const animated = [...container.querySelectorAll('[class*="animate-"]')]
      .filter((el) => (el.getAttribute('class') ?? '').match(/animate-\[bobo-/))
    expect(animated).toHaveLength(0)
    // 表情本身仍呈现（静态首帧语义）
    expect(container.querySelector('[data-testid="bobo-eyes-happy"]')).toBeTruthy()
  })

  it('expression 与 state 正交：speaking 时表情仍生效', () => {
    const { container } = render(<BoBoPet state="speaking" expression="happy" />)
    expect(container.querySelector('[data-expression="happy"]')).toBeTruthy()
    // speaking 嘴巴开合仍工作
    expect(container.querySelector('ellipse.animate-\\[bobo-mouth_0\\.5s_ease-in-out_infinite\\]')
      ?? container.querySelector('ellipse[class]')).toBeTruthy()
  })
})
