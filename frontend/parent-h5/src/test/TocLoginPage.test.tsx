/**
 * doing/85 TOC-001：TocLoginPage 页面测试
 * 覆盖：手机号/验证码校验、验证码发送（回显 + 倒计时）、登录/注册成功与失败路径。
 * 模式：Taro mock + services mock + fireEvent（VerifyPage.test.tsx 同构）。
 */
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import TocLoginPage from '../pages/toc-login/index'

// Mock Taro 导航
vi.mock('@tarojs/taro', () => ({
  default: { navigateTo: vi.fn() }
}))

// Mock 服务层
vi.mock('../services/toc', () => ({
  sendTocCode: vi.fn(),
  tocRegister: vi.fn(),
  tocLogin: vi.fn(),
  saveTocSession: vi.fn(),
}))

import Taro from '@tarojs/taro'
import { sendTocCode, tocRegister, tocLogin, saveTocSession } from '../services/toc'

const mockedTaro = vi.mocked(Taro)
const mockedSendTocCode = vi.mocked(sendTocCode)
const mockedTocRegister = vi.mocked(tocRegister)
const mockedTocLogin = vi.mocked(tocLogin)
const mockedSaveTocSession = vi.mocked(saveTocSession)

const fakeSession = { token: 'toc-tk', familyAccountId: 'fam-1', phone: '13800138000', displayName: '家庭' }

function fillPhone(value: string) {
  fireEvent.input(screen.getByPlaceholderText('请输入手机号'), { target: { value } })
}

function fillCode(value: string) {
  fireEvent.input(screen.getByPlaceholderText('请输入验证码'), { target: { value } })
}

describe('TocLoginPage', () => {
  beforeEach(() => {
    mockedTaro.navigateTo.mockClear()
    mockedSendTocCode.mockReset()
    mockedTocRegister.mockReset()
    mockedTocLogin.mockReset()
    mockedSaveTocSession.mockClear()
    mockedSendTocCode.mockResolvedValue({ code: '654321' })
    mockedTocLogin.mockResolvedValue(fakeSession)
    mockedTocRegister.mockResolvedValue(fakeSession)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染页面标题与表单', () => {
    render(<TocLoginPage />)
    expect(screen.getByText('波波小伙伴')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入手机号')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('请输入验证码')).toBeInTheDocument()
  })

  it('手机号为空点获取验证码 → 提示请输入手机号', async () => {
    render(<TocLoginPage />)
    fireEvent.click(screen.getByText('获取验证码'))
    expect(await screen.findByText('请输入手机号')).toBeInTheDocument()
    expect(mockedSendTocCode).not.toHaveBeenCalled()
  })

  it('手机号格式错误 → 提示请输入 11 位手机号', async () => {
    render(<TocLoginPage />)
    fillPhone('123')
    fireEvent.click(screen.getByText('获取验证码'))
    expect(await screen.findByText('请输入 11 位手机号')).toBeInTheDocument()
    expect(mockedSendTocCode).not.toHaveBeenCalled()
  })

  it('发送验证码成功 → 回显演示验证码并启动 60s 倒计时', async () => {
    vi.useFakeTimers()
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fireEvent.click(screen.getByText('获取验证码'))
    // fake timers 下 findBy 轮询不可用：flush 微任务后同步断言
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getByText('演示环境验证码：654321')).toBeInTheDocument()
    expect(screen.getByText('60s')).toBeInTheDocument()
    // 倒计时递减（fake timers 推进 1s）
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(screen.getByText('59s')).toBeInTheDocument()
  })

  it('发送验证码失败 → 显示错误消息', async () => {
    mockedSendTocCode.mockRejectedValue(new Error('发送过于频繁'))
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fireEvent.click(screen.getByText('获取验证码'))
    expect(await screen.findByText('发送过于频繁')).toBeInTheDocument()
  })

  it('登录：验证码为空 → 提示请输入验证码', async () => {
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fireEvent.click(screen.getByText('登录'))
    expect(await screen.findByText('请输入验证码')).toBeInTheDocument()
    expect(mockedTocLogin).not.toHaveBeenCalled()
  })

  it('登录：验证码格式错误 → 提示请输入 6 位验证码', async () => {
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fillCode('12')
    fireEvent.click(screen.getByText('登录'))
    expect(await screen.findByText('请输入 6 位验证码')).toBeInTheDocument()
    expect(mockedTocLogin).not.toHaveBeenCalled()
  })

  it('登录成功 → 保存会话并跳转家庭档案页', async () => {
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fillCode('654321')
    fireEvent.click(screen.getByText('登录'))
    await waitFor(() => expect(mockedSaveTocSession).toHaveBeenCalledWith(fakeSession))
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/pages/toc-profiles/index' })
  })

  it('登录失败 → 显示错误消息', async () => {
    mockedTocLogin.mockRejectedValue(new Error('验证码已过期'))
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fillCode('654321')
    fireEvent.click(screen.getByText('登录'))
    expect(await screen.findByText('验证码已过期')).toBeInTheDocument()
  })

  it('注册成功 → 保存会话并跳转', async () => {
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fillCode('654321')
    fireEvent.click(screen.getByText('注册新账号'))
    await waitFor(() => expect(mockedSaveTocSession).toHaveBeenCalledWith(fakeSession))
    expect(mockedTaro.navigateTo).toHaveBeenCalledWith({ url: '/pages/toc-profiles/index' })
  })

  it('注册失败 → 显示错误消息', async () => {
    mockedTocRegister.mockRejectedValue(new Error('该手机号已注册'))
    render(<TocLoginPage />)
    fillPhone('13800138000')
    fillCode('654321')
    fireEvent.click(screen.getByText('注册新账号'))
    expect(await screen.findByText('该手机号已注册')).toBeInTheDocument()
  })
})
