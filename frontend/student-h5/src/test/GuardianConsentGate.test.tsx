/**
 * GuardianConsentGate 单测（P0-1 审计修复，AUTH-040 监护人同意前端闭环，PIPL §31）
 *
 * 契约：
 * - 步骤一：输入家长手机号（11 位校验）→ 发送验证码（requestGuardianConsent）
 * - 步骤二：输入短信验证码 → 确认（confirmGuardianConsent）→ 成功回调 onSuccess
 * - 失败展示儿童友好错误，不白屏；确认成功后进入主界面
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import GuardianConsentGate from '../components/GuardianConsentGate'
import { requestGuardianConsent, confirmGuardianConsent } from '../api'

vi.mock('../api', () => ({
  requestGuardianConsent: vi.fn(),
  confirmGuardianConsent: vi.fn(),
}))

const mockRequest = requestGuardianConsent as ReturnType<typeof vi.fn>
const mockConfirm = confirmGuardianConsent as ReturnType<typeof vi.fn>

describe('GuardianConsentGate', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRequest.mockResolvedValue(undefined)
    mockConfirm.mockResolvedValue(undefined)
  })

  it('渲染儿童友好说明与家长手机号输入', () => {
    render(<GuardianConsentGate onSuccess={vi.fn()} />)
    expect(screen.getByRole('heading', { name: /家长同意/ })).toBeTruthy()
    expect(screen.getByPlaceholderText(/手机号/)).toBeTruthy()
  })

  it('手机号格式不合法时不发请求并提示', async () => {
    render(<GuardianConsentGate onSuccess={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText(/手机号/), { target: { value: '123' } })
    fireEvent.click(screen.getByRole('button', { name: /发送验证码/ }))
    await waitFor(() => expect(mockRequest).not.toHaveBeenCalled())
    expect(screen.getByText(/正确的.*手机号/)).toBeTruthy()
  })

  it('合法手机号发送验证码后进入输入验证码步骤', async () => {
    render(<GuardianConsentGate onSuccess={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: /发送验证码/ }))
    await waitFor(() => expect(mockRequest).toHaveBeenCalledWith('13800138000'))
    expect(screen.getByPlaceholderText(/验证码/)).toBeTruthy()
  })

  it('验证码确认后触发 onSuccess', async () => {
    const onSuccess = vi.fn()
    render(<GuardianConsentGate onSuccess={onSuccess} />)
    fireEvent.change(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: /发送验证码/ }))
    await waitFor(() => expect(mockRequest).toHaveBeenCalled())

    fireEvent.change(screen.getByPlaceholderText(/验证码/), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: /确认/ }))
    await waitFor(() => expect(mockConfirm).toHaveBeenCalledWith('13800138000', '123456'))
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
  })

  it('确认失败展示错误信息且不回调', async () => {
    mockConfirm.mockRejectedValue(new Error('验证码不正确'))
    const onSuccess = vi.fn()
    render(<GuardianConsentGate onSuccess={onSuccess} />)
    fireEvent.change(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: /发送验证码/ }))
    await waitFor(() => expect(mockRequest).toHaveBeenCalled())

    fireEvent.change(screen.getByPlaceholderText(/验证码/), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: /确认/ }))
    await waitFor(() => expect(screen.getByText(/验证码不正确/)).toBeTruthy())
    expect(onSuccess).not.toHaveBeenCalled()
  })

  it('发送验证码失败展示错误且不进入第二步', async () => {
    mockRequest.mockRejectedValue(new Error('短信发送太频繁，请稍后再试'))
    render(<GuardianConsentGate onSuccess={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText(/手机号/), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByRole('button', { name: /发送验证码/ }))
    await waitFor(() => expect(screen.getByText(/短信发送太频繁/)).toBeTruthy())
    expect(screen.queryByPlaceholderText(/验证码/)).toBeNull()
  })
})
