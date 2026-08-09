import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import LoginPage from '../pages/LoginPage'

vi.mock('../api', () => ({
  platformLogin: vi.fn(),
}))

import { platformLogin } from '../api'

describe('LoginPage', () => {
  beforeEach(() => {
    vi.mocked(platformLogin).mockReset()
    localStorage.clear()
  })

  it('渲染登录表单（用户名/密码/登录按钮）', () => {
    render(<LoginPage onLogin={() => {}} />)
    expect(screen.getByLabelText('用户名')).toBeInTheDocument()
    expect(screen.getByLabelText('密码')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^登\s*录$/ })).toBeInTheDocument()
  })

  it('提交成功 → onLogin 携带角色与显示名', async () => {
    vi.mocked(platformLogin).mockResolvedValue({ token: 'PLATFORM_xxx', role: 'ops_admin', displayName: '运维' })
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'ops' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: /^登\s*录$/ }))

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith('ops_admin', '运维'))
    expect(platformLogin).toHaveBeenCalledWith('ops', 'secret')
  })

  it('提交失败 → 不触发 onLogin（错误提示）', async () => {
    vi.mocked(platformLogin).mockRejectedValue(new Error('用户名或密码错误'))
    const onLogin = vi.fn()
    render(<LoginPage onLogin={onLogin} />)

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'ops' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'bad' } })
    fireEvent.click(screen.getByRole('button', { name: /^登\s*录$/ }))

    await waitFor(() => expect(onLogin).not.toHaveBeenCalled())
  })
})
