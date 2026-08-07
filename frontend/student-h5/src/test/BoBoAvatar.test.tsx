import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import BoBoAvatar from '../components/BoBoAvatar'

describe('BoBoAvatar（品牌图形统一，doing/75 §7.5-2）', () => {
  it('渲染 SVG 海豚头像（testid + aria-label 波波）', () => {
    render(<BoBoAvatar />)
    const svg = screen.getByTestId('bobo-avatar')
    expect(svg).toBeTruthy()
    expect(svg.getAttribute('role')).toBe('img')
    expect(svg.getAttribute('aria-label')).toBe('波波')
  })

  it('默认使用海洋主题色（body #38BDF8 / belly #E0F2FE / fin #0284C7）', () => {
    render(<BoBoAvatar />)
    const paths = screen.getByTestId('bobo-avatar').querySelectorAll('path')
    // 身体主色 path 含 fill=#38BDF8；背鳍 fill=#0284C7；圆底 fill=#E0F2FE
    expect([...paths].some((p) => p.getAttribute('fill') === '#38BDF8')).toBe(true)
    expect([...paths].some((p) => p.getAttribute('fill') === '#0284C7')).toBe(true)
    expect(screen.getByTestId('bobo-avatar').querySelector('circle')?.getAttribute('fill')).toBe('#E0F2FE')
  })

  it('随主题换色（colors 三元组覆盖 body/belly/fin）', () => {
    render(<BoBoAvatar colors={{ body: '#F472B6', belly: '#FCE7F3', fin: '#DB2777' }} />)
    const svg = screen.getByTestId('bobo-avatar')
    const paths = svg.querySelectorAll('path')
    expect([...paths].some((p) => p.getAttribute('fill') === '#F472B6')).toBe(true)
    expect([...paths].some((p) => p.getAttribute('fill') === '#DB2777')).toBe(true)
    expect(svg.querySelector('circle')?.getAttribute('fill')).toBe('#FCE7F3')
  })

  it('size 与 className 透传', () => {
    render(<BoBoAvatar size={96} className="mascot" />)
    const svg = screen.getByTestId('bobo-avatar')
    expect(svg.getAttribute('width')).toBe('96')
    expect(svg.getAttribute('height')).toBe('96')
    expect(svg.getAttribute('class')).toBe('mascot')
  })
})
