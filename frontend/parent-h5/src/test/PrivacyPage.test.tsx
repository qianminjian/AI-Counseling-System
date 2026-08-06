import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect } from 'vitest'
import PrivacyPage from '../pages/privacy/index'

describe('PrivacyPage（F-5 PIPL 个人信息保护告知）', () => {
  it('渲染页面标题', () => {
    render(
      <MemoryRouter>
        <PrivacyPage />
      </MemoryRouter>
    )
    expect(screen.getByRole('heading', { name: /个人信息保护告知/ })).toBeInTheDocument()
  })

  it('包含核心告知要点（收集范围/用途/未成年人保护/家长权利）', () => {
    render(
      <MemoryRouter>
        <PrivacyPage />
      </MemoryRouter>
    )
    expect(screen.getAllByText(/收集/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/使用/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/未成年人/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/权利/).length).toBeGreaterThanOrEqual(1)
  })

  it('提供返回登录链接', () => {
    render(
      <MemoryRouter>
        <PrivacyPage />
      </MemoryRouter>
    )
    const back = screen.getByRole('link', { name: /返回登录/ })
    expect(back).toHaveAttribute('href', '/parent/')
  })
})
