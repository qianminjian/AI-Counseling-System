import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import SceneDecor from '../components/SceneDecor'

describe('SceneDecor', () => {
  it('ocean 主题渲染气泡和游鱼', () => {
    const { container } = render(<SceneDecor themeId="ocean" />)
    expect(container.querySelectorAll('.bubble').length).toBe(8)
    expect(container.querySelectorAll('.fish').length).toBe(3)
    expect(container.querySelector('.sea-floor')).toBeTruthy()
  })

  it('garden 主题渲染糖果漂浮', () => {
    const { container } = render(<SceneDecor themeId="garden" />)
    expect(container.querySelectorAll('.candy-float').length).toBe(6)
  })

  it('rainbow 主题渲染星星和行星', () => {
    const { container } = render(<SceneDecor themeId="rainbow" />)
    expect(container.querySelectorAll('.star').length).toBe(30)
    expect(container.querySelectorAll('.planet').length).toBe(3)
    expect(container.querySelectorAll('.shooting-star').length).toBe(2)
  })

  it('未知主题回退到 rainbow', () => {
    const { container } = render(<SceneDecor themeId="unknown" />)
    expect(container.querySelectorAll('.star').length).toBe(30)
  })
})
