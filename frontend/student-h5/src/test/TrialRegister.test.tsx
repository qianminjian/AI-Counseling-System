import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('../api', () => ({
  trialRegister: vi.fn(),
  setToken: vi.fn(),
  setRefreshToken: vi.fn(),
  setUser: vi.fn(),
  requestGuardianConsent: vi.fn(),
  confirmGuardianConsent: vi.fn(),
}))

import TrialRegister from '../components/TrialRegister'
import { trialRegister, setToken, setUser, requestGuardianConsent, confirmGuardianConsent } from '../api'

describe('TrialRegister', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染注册表单所有字段', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    expect(screen.getByText('欢迎体验 MindSafe')).toBeTruthy()
    expect(screen.getByPlaceholderText('请输入邀请码')).toBeTruthy()
    expect(screen.getByPlaceholderText('给自己取个名字吧')).toBeTruthy()
    expect(screen.getByPlaceholderText('您的年龄')).toBeTruthy()
    expect(screen.getByText('开始体验 🚀')).toBeTruthy()
  })

  it('未填必填项提交显示错误', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.click(screen.getByText('开始体验 🚀'))
    expect(screen.getByText('请填写所有必填项')).toBeTruthy()
  })

  it('无效年龄显示错误', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    // 绕过 number input min=6 的约束校验（jsdom 会阻止 submit），直接触发 form submit
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '5' } })
    const form = document.querySelector('form')!
    fireEvent.submit(form)
    expect(screen.getByText(/有效年龄/)).toBeTruthy()
  })

  it('昵称过短显示错误', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花' } })
    fireEvent.click(screen.getByRole('button', { name: /男生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    expect(screen.getByText('昵称长度 2-12 字')).toBeTruthy()
  })

  it('年龄 < 14 显示监护人手机号字段和警告', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '10' } })
    expect(screen.getByPlaceholderText('爸爸或妈妈的手机号')).toBeTruthy()
    expect(screen.getByText(/不满 14 周岁需监护人同意/)).toBeTruthy()
  })

  it('年龄 < 14 无监护人手机号显示错误', () => {
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '10' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    expect(screen.getByText('不满 14 周岁需填写正确的监护人手机号')).toBeTruthy()
  })

  it('注册成功无家庭码直接回调 onRegistered', async () => {
    ;(trialRegister as any).mockResolvedValue({ token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花' })
    const onRegistered = vi.fn()
    render(<TrialRegister consentVersion="v1" onRegistered={onRegistered} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(onRegistered).toHaveBeenCalled())
    expect(setToken).toHaveBeenCalledWith('tk')
    expect(setUser).toHaveBeenCalled()
  })

  it('注册成功有家庭码显示家庭码页面', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花', familyCode: 'ABC123',
    })
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('注册成功！')).toBeTruthy())
    expect(screen.getByText('ABC123')).toBeTruthy()
  })

  it('家庭码页面：家长绑定表单验证', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花', familyCode: 'ABC123',
    })
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('注册成功！')).toBeTruthy())

    // 展开绑定表单
    fireEvent.click(screen.getByText('👨‍👩‍👧 我是家长，现在绑定手机号'))
    // 手机号格式错误
    fireEvent.change(screen.getByPlaceholderText('家长手机号'), { target: { value: '123' } })
    fireEvent.change(screen.getByPlaceholderText('设置密码（至少 6 位）'), { target: { value: '123456' } })
    fireEvent.click(screen.getByText('确认绑定'))
    expect(screen.getByText('请输入正确的手机号')).toBeTruthy()
  })

  it('注册失败显示错误', async () => {
    ;(trialRegister as any).mockRejectedValue(new Error('邀请码无效'))
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'BAD' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('邀请码无效')).toBeTruthy())
  })

  it('guardianConsentPending 进入监护人确认页', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      familyCode: 'FAM1', guardianConsentPending: true,
    })
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('爸爸或妈妈的手机号'), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('需要家长同意')).toBeTruthy())
    expect(screen.getByText('138****8000')).toBeTruthy()
  })

  it('监护人确认页：发送验证码 + 确认', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      guardianConsentPending: true,
    })
    ;(requestGuardianConsent as any).mockResolvedValue({})
    ;(confirmGuardianConsent as any).mockResolvedValue({})
    const onRegistered = vi.fn()
    render(<TrialRegister consentVersion="v1" onRegistered={onRegistered} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('爸爸或妈妈的手机号'), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('需要家长同意')).toBeTruthy())

    // 发送验证码
    fireEvent.click(screen.getByText('发送验证码'))
    await waitFor(() => expect(screen.getByText(/验证码已发送/)).toBeTruthy())

    // 输入验证码并确认
    fireEvent.change(screen.getByPlaceholderText('6 位验证码'), { target: { value: '123456' } })
    fireEvent.click(screen.getByText('确认同意'))
    await waitFor(() => expect(onRegistered).toHaveBeenCalled())
  })

  it('监护人确认页：验证码格式错误', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花',
      familyCode: 'FAM1', guardianConsentPending: true,
    })
    ;(requestGuardianConsent as any).mockResolvedValue({})
    render(<TrialRegister consentVersion="v1" onRegistered={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '10' } })
    fireEvent.change(screen.getByPlaceholderText('爸爸或妈妈的手机号'), { target: { value: '13800138000' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('需要家长同意')).toBeTruthy())

    fireEvent.click(screen.getByText('发送验证码'))
    await waitFor(() => expect(screen.getByText(/验证码已发送/)).toBeTruthy())
    fireEvent.change(screen.getByPlaceholderText('6 位验证码'), { target: { value: '12' } })
    fireEvent.click(screen.getByText('确认同意'))
    expect(screen.getByText('请输入 6 位数字验证码')).toBeTruthy()
  })

  it('家庭码页面点击"开始使用"回调 onRegistered', async () => {
    ;(trialRegister as any).mockResolvedValue({
      token: 'tk', userId: 'u1', userType: 'student', pseudonym: '花花', familyCode: 'XYZ789',
    })
    const onRegistered = vi.fn()
    render(<TrialRegister consentVersion="v1" onRegistered={onRegistered} />)
    fireEvent.change(screen.getByPlaceholderText('请输入邀请码'), { target: { value: 'DEMO2026' } })
    fireEvent.change(screen.getByPlaceholderText('给自己取个名字吧'), { target: { value: '花花' } })
    fireEvent.click(screen.getByRole('button', { name: /女生/ }))
    fireEvent.change(screen.getByPlaceholderText('您的年龄'), { target: { value: '20' } })
    fireEvent.click(screen.getByText('开始体验 🚀'))
    await waitFor(() => expect(screen.getByText('注册成功！')).toBeTruthy())
    fireEvent.click(screen.getByText('开始使用 🚀'))
    expect(onRegistered).toHaveBeenCalled()
  })
})
