import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect } from 'vitest'
import VerifyPage from '../pages/verify/index'

describe('VerifyPage', () => {
  it('渲染登录/注册切换按钮', () => {
    render(
      <MemoryRouter>
        <VerifyPage />
      </MemoryRouter>
    )
    expect(screen.getAllByText(/登录/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/注册/).length).toBeGreaterThanOrEqual(1)
  })

  it('渲染手机号输入框', () => {
    render(
      <MemoryRouter>
        <VerifyPage />
      </MemoryRouter>
    )
    const phoneInput = screen.getByPlaceholderText(/手机号/)
    expect(phoneInput).toBeInTheDocument()
  })

  it('渲染密码输入框', () => {
    render(
      <MemoryRouter>
        <VerifyPage />
      </MemoryRouter>
    )
    const pwdInput = screen.getByPlaceholderText(/密码/)
    expect(pwdInput).toBeInTheDocument()
  })
})
