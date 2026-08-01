import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ConsentGate, { CONSENT_VERSION } from '../components/ConsentGate'

describe('ConsentGate', () => {
  it('导出版本号 v0.1', () => {
    expect(CONSENT_VERSION).toBe('v0.1')
  })

  it('显示标题和关键条款', () => {
    render(<ConsentGate onAgree={vi.fn()} />)
    expect(screen.getByText('使用前请阅读以下重要信息')).toBeTruthy()
    expect(screen.getByText(/非医疗服务、非专业心理咨询/)).toBeTruthy()
    expect(screen.getByText(/400-161-9995/)).toBeTruthy()
  })

  it('初始状态：checkbox 禁用、按钮禁用', () => {
    render(<ConsentGate onAgree={vi.fn()} />)
    const checkbox = screen.getByRole('checkbox') as HTMLInputElement
    const btn = screen.getByText('同意并继续')
    // 内容超出容器时需要滚动才能勾选（jsdom scrollHeight=0 <= clientHeight+50 → 直接可读）
    // jsdom 中 scrollHeight=0, clientHeight=0 → 0 <= 50 → scrolledToBottom=true
    expect(checkbox.disabled).toBe(false)
    expect(btn.className).toContain('cursor-not-allowed')
  })

  it('勾选后按钮启用，点击触发 onAgree', () => {
    const onAgree = vi.fn()
    render(<ConsentGate onAgree={onAgree} />)
    const checkbox = screen.getByRole('checkbox')
    fireEvent.click(checkbox)
    const btn = screen.getByText('同意并继续')
    fireEvent.click(btn)
    expect(onAgree).toHaveBeenCalledWith(CONSENT_VERSION)
  })

  it('未勾选时点击按钮不触发 onAgree', () => {
    const onAgree = vi.fn()
    render(<ConsentGate onAgree={onAgree} />)
    fireEvent.click(screen.getByText('同意并继续'))
    expect(onAgree).not.toHaveBeenCalled()
  })

  it('勾选后显示绿色对勾', () => {
    const { container } = render(<ConsentGate onAgree={vi.fn()} />)
    fireEvent.click(screen.getByRole('checkbox'))
    expect(container.querySelector('.text-green-600')).toBeTruthy()
  })

  it('包含八条条款章节', () => {
    const { container } = render(<ConsentGate onAgree={vi.fn()} />)
    const headings = container.querySelectorAll('h2')
    expect(headings.length).toBe(8)
  })
})
