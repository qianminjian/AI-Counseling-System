import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, it, expect } from 'vitest'
import PrivacyPage from '../pages/privacy/index'

// 与生产 BrowserRouter basename="/parent" 对齐（react-router v8：initialEntries 需含 basename 完整路径）
const renderPage = () => {
  const router = createMemoryRouter(
    [{ path: '/privacy', element: <PrivacyPage /> }],
    { initialEntries: ['/parent/privacy'], basename: '/parent' }
  )
  return render(<RouterProvider router={router} />)
}

describe('PrivacyPage（F-5 PIPL 个人信息保护告知）', () => {
  it('渲染页面标题', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /个人信息保护告知/ })).toBeInTheDocument()
  })

  it('包含核心告知要点（收集范围/用途/未成年人保护/家长权利）', () => {
    renderPage()
    expect(screen.getAllByText(/收集/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/使用/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/未成年人/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/权利/).length).toBeGreaterThanOrEqual(1)
  })

  it('提供返回登录链接', () => {
    renderPage()
    const back = screen.getByRole('link', { name: /返回登录/ })
    // react-router v8：basename 渲染不带尾斜杠（Link to="/" + basename="/parent" → href="/parent"），与生产一致
    expect(back).toHaveAttribute('href', '/parent')
  })
})
