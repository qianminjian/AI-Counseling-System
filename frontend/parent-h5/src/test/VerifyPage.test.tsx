// doing/73 T3：VerifyPage Taro 化测试（Taro 组件渲染 + Taro 导航 mock + fireEvent 交互）
// spike 结论（R7）：children 渲染可用；input/submit 需 fireEvent 显式派发（jsdom 下 Stencil 未初始化）
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import VerifyPage from '../pages/verify/index'

// Mock Taro 导航（nav.ts 经 Taro.redirectTo/navigateTo 跳转）
vi.mock('@tarojs/taro', () => ({
  default: { redirectTo: vi.fn(), navigateTo: vi.fn() }
}))

// Mock 服务层（避免真实网络）
vi.mock('../services/index', () => ({
  parentLogin: vi.fn(),
  parentRegister: vi.fn()
}))

import Taro from '@tarojs/taro'
import { parentLogin } from '../services/index'

const mockedTaro = vi.mocked(Taro)

describe('VerifyPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
    mockedTaro.redirectTo.mockClear()
    mockedTaro.navigateTo.mockClear()
    vi.mocked(parentLogin).mockReset()
  })

  it('渲染登录/注册切换按钮', () => {
    render(<VerifyPage />)
    expect(screen.getAllByText(/登录/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/注册/).length).toBeGreaterThanOrEqual(1)
  })

  it('渲染手机号输入框', () => {
    render(<VerifyPage />)
    expect(screen.getByPlaceholderText(/手机号/)).toBeInTheDocument()
  })

  it('渲染密码输入框', () => {
    render(<VerifyPage />)
    expect(screen.getByPlaceholderText(/密码/)).toBeInTheDocument()
  })

  it('F-5：登录/注册页展示个人信息保护告知入口（PIPL 合规），点击跳转隐私页', async () => {
    render(<VerifyPage />)
    const entry = screen.getByText(/个人信息保护告知/)
    expect(entry).toBeInTheDocument()
    fireEvent.click(entry)
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/privacy' })
  })

  it('已登录时重定向到周报页（不渲染表单）', () => {
    sessionStorage.setItem('parent_token', 'tk')
    render(<VerifyPage />)
    expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/report' })
    expect(screen.queryByPlaceholderText(/手机号/)).not.toBeInTheDocument()
  })

  it('登录成功：提交表单 → 写入认证 → 跳转周报页', async () => {
    vi.mocked(parentLogin).mockResolvedValue({
      data: {
        token: 'tk1', refreshToken: 'rt1', parentId: 'p1',
        displayName: '家长', children: []
      }
    })
    const { container } = render(<VerifyPage />)
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(parentLogin).toHaveBeenCalledWith({ phone: '13800138000', password: 'secret1' })
      expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/report' })
    })
    expect(sessionStorage.getItem('parent_token')).toBe('tk1')
    expect(sessionStorage.getItem('parent_refresh')).toBe('rt1')
  })

  it('手机号非法时显示校验错误（不调用 API）', async () => {
    const { container } = render(<VerifyPage />)
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '123' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(screen.getByText('请输入正确的 11 位手机号')).toBeInTheDocument()
    })
    expect(parentLogin).not.toHaveBeenCalled()
  })
})
