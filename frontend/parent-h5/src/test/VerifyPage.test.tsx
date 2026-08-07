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
import { parentLogin, parentRegister } from '../services/index'

const mockedTaro = vi.mocked(Taro)

// 切换到注册模式（点击“首次注册” tab）
function switchToRegister() {
  fireEvent.click(screen.getByText('首次注册'))
}

describe('VerifyPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
    mockedTaro.redirectTo.mockClear()
    mockedTaro.navigateTo.mockClear()
    vi.mocked(parentLogin).mockReset()
    vi.mocked(parentRegister).mockReset()
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

  it('密码过短时显示校验错误（不调用 API）', async () => {
    const { container } = render(<VerifyPage />)
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: '123' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(screen.getByText('密码至少 6 位')).toBeInTheDocument()
    })
    expect(parentLogin).not.toHaveBeenCalled()
  })

  it('切换注册模式：渲染家庭码输入框与关系选择', () => {
    render(<VerifyPage />)
    expect(screen.queryByPlaceholderText(/6 位码/)).not.toBeInTheDocument()
    switchToRegister()
    expect(screen.getByPlaceholderText(/6 位码/)).toBeInTheDocument()
    expect(screen.getByText(/妈妈/)).toBeInTheDocument()
    expect(screen.getByText(/爸爸/)).toBeInTheDocument()
  })

  it('注册模式缺家庭码时显示校验错误（不调用 API）', async () => {
    const { container } = render(<VerifyPage />)
    switchToRegister()
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(screen.getByText('请输入孩子给您的家庭码')).toBeInTheDocument()
    })
    expect(parentRegister).not.toHaveBeenCalled()
  })

  it('注册成功：提交表单 → parentRegister 参数 → 写入认证 → 跳转周报页', async () => {
    vi.mocked(parentRegister).mockResolvedValue({
      data: {
        token: 'tk2', refreshToken: 'rt2', parentId: 'p2',
        displayName: '家长', children: [{ id: 'c1', name: '小明' }]
      }
    })
    const { container } = render(<VerifyPage />)
    switchToRegister()
    fireEvent.input(screen.getByPlaceholderText(/6 位码/), { target: { value: 'ABC123' } })
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    // 切换关系：妈妈 → 爸爸
    fireEvent.click(screen.getByText(/爸爸/))
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(parentRegister).toHaveBeenCalledWith({
        familyCode: 'ABC123', phone: '13800138000', password: 'secret1', relation: 'father'
      })
      expect(mockedTaro.redirectTo).toHaveBeenCalledWith({ url: '/report' })
    })
    expect(sessionStorage.getItem('parent_token')).toBe('tk2')
    expect(sessionStorage.getItem('parent_user')).toContain('小明')
  })

  it('登录 API 失败：显示错误消息且不跳转', async () => {
    vi.mocked(parentLogin).mockRejectedValue(new Error('账号或密码错误'))
    const { container } = render(<VerifyPage />)
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(screen.getByText('账号或密码错误')).toBeInTheDocument()
    })
    expect(mockedTaro.redirectTo).not.toHaveBeenCalled()
  })

  it('提交后按钮进入处理中状态（loading）', async () => {
    let resolveLogin!: (v: unknown) => void
    vi.mocked(parentLogin).mockReturnValue(new Promise((r) => { resolveLogin = r }))
    const { container } = render(<VerifyPage />)
    fireEvent.input(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.input(screen.getByPlaceholderText(/密码/), { target: { value: 'secret1' } })
    fireEvent.submit(container.querySelector('taro-form-core')!)

    await waitFor(() => {
      expect(screen.getByText('处理中...')).toBeInTheDocument()
    })
    resolveLogin({ data: { token: 'tk3', refreshToken: 'rt3', parentId: 'p3', displayName: '家长', children: [] } })
    await waitFor(() => {
      expect(screen.queryByText('处理中...')).not.toBeInTheDocument()
    })
  })
})
