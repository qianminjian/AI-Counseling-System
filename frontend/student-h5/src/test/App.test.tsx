import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

// mock 所有子组件和依赖
vi.mock('../theme/ThemeProvider', () => ({
  ThemeProvider: ({ children }: any) => <div>{children}</div>,
}))
vi.mock('../components/ConsentGate', () => ({
  default: ({ onAgree }: any) => (
    <div data-testid="consent-gate">
      <button onClick={onAgree}>同意</button>
    </div>
  ),
}))
vi.mock('../components/LoginPage', () => ({
  default: ({ onLogin, onRegister, onNeedConsent }: any) => (
    <div data-testid="login-page">
      <button onClick={onLogin}>登录成功</button>
      <button onClick={onRegister}>注册成功</button>
      <button onClick={onNeedConsent}>需要同意</button>
    </div>
  ),
}))
vi.mock('../components/WelcomeGuide', () => ({
  default: () => <div data-testid="welcome-guide" />,
}))
vi.mock('../components/EmotionSelect', () => ({
  default: ({ onStart, onLogout }: any) => (
    <div data-testid="emotion-select">
      <button onClick={() => onStart({ sessionId: 's1', greeting: '你好', emotionTag: 'neutral' })}>开始聊天</button>
      <button onClick={onLogout}>退出</button>
    </div>
  ),
}))
vi.mock('../components/ChatRoom', () => ({
  default: ({ session, onEnd }: any) => (
    <div data-testid="chat-room">
      <span>{session.sessionId}</span>
      <button onClick={onEnd}>结束会话</button>
    </div>
  ),
}))
vi.mock('../components/IdleWarning', () => ({
  default: ({ secondsLeft, onStay }: any) => (
    <div data-testid="idle-warning">
      <span>{secondsLeft}</span>
      <button onClick={onStay}>留在这里</button>
    </div>
  ),
}))
vi.mock('../hooks/useIdleLogout', () => ({
  useIdleLogout: () => ({ warning: false, secondsLeft: 60, stay: vi.fn() }),
}))

const mockIsAuthenticated = vi.fn()
const mockGetUser = vi.fn()
const mockClearToken = vi.fn()
const mockIsConsentDone = vi.fn()
const mockMarkConsentDone = vi.fn()
vi.mock('../api', () => ({
  isAuthenticated: () => mockIsAuthenticated(),
  getUser: () => mockGetUser(),
  clearToken: () => mockClearToken(),
  isConsentDone: () => mockIsConsentDone(),
  markConsentDone: () => mockMarkConsentDone(),
}))

import App from '../App'

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockIsAuthenticated.mockReturnValue(false)
    mockGetUser.mockReturnValue(null)
    mockIsConsentDone.mockReturnValue(true)
    // 确保不在 /parent 路径
    Object.defineProperty(window, 'location', {
      value: { pathname: '/' },
      writable: true,
      configurable: true,
    })
  })

  it('未认证显示登录页', () => {
    render(<App />)
    expect(screen.getByTestId('login-page')).toBeTruthy()
  })

  it('登录成功进入情绪选择', () => {
    mockGetUser.mockReturnValue({ pseudonym: '小明' })
    render(<App />)
    fireEvent.click(screen.getByText('登录成功'))
    expect(screen.getByTestId('emotion-select')).toBeTruthy()
    expect(screen.getByTestId('welcome-guide')).toBeTruthy()
  })

  it('注册成功标记同意并进入情绪选择', () => {
    mockGetUser.mockReturnValue({ pseudonym: '花花' })
    render(<App />)
    fireEvent.click(screen.getByText('注册成功'))
    expect(mockMarkConsentDone).toHaveBeenCalled()
    expect(screen.getByTestId('emotion-select')).toBeTruthy()
  })

  it('选择情绪后进入聊天室', () => {
    mockGetUser.mockReturnValue({ pseudonym: '小明' })
    render(<App />)
    fireEvent.click(screen.getByText('登录成功'))
    fireEvent.click(screen.getByText('开始聊天'))
    expect(screen.getByTestId('chat-room')).toBeTruthy()
    expect(screen.getByText('s1')).toBeTruthy()
  })

  it('结束会话回到情绪选择', () => {
    mockGetUser.mockReturnValue({ pseudonym: '小明' })
    render(<App />)
    fireEvent.click(screen.getByText('登录成功'))
    fireEvent.click(screen.getByText('开始聊天'))
    fireEvent.click(screen.getByText('结束会话'))
    expect(screen.getByTestId('emotion-select')).toBeTruthy()
  })

  it('退出登录清除 token 回登录页', () => {
    mockGetUser.mockReturnValue({ pseudonym: '小明' })
    render(<App />)
    fireEvent.click(screen.getByText('登录成功'))
    fireEvent.click(screen.getByText('退出'))
    expect(mockClearToken).toHaveBeenCalled()
    expect(screen.getByTestId('login-page')).toBeTruthy()
  })

  it('未完成告知同意时点注册显示 ConsentGate', () => {
    mockIsConsentDone.mockReturnValue(false)
    render(<App />)
    fireEvent.click(screen.getByText('需要同意'))
    expect(screen.getByTestId('consent-gate')).toBeTruthy()
    // 同意后回到注册
    fireEvent.click(screen.getByText('同意'))
    expect(mockMarkConsentDone).toHaveBeenCalled()
    expect(screen.getByTestId('login-page')).toBeTruthy()
  })

  it('已认证（有 token）直接显示情绪选择', () => {
    mockIsAuthenticated.mockReturnValue(true)
    mockGetUser.mockReturnValue({ pseudonym: '小明' })
    render(<App />)
    expect(screen.getByTestId('emotion-select')).toBeTruthy()
  })
})
