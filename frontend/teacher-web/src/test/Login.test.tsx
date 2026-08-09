import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 登录页测试（ARCH-009 E-2 补测）
 * - 渲染用户名/密码表单
 * - 企业微信登录链接：enabled=true 时展示，否则不展示
 * - 表单提交成功 → setToken/setRefreshToken + onLogin
 * - 提交失败 → 不调用 onLogin（message.error 兜底）
 */

const mockCallEndpoint = vi.fn();
vi.mock('../api', () => ({
  callEndpoint: (key: string, options?: unknown) => mockCallEndpoint(key, options),
  setToken: (t: string) => { sessionStorage.setItem('mindsafe_token', t); },
  setRefreshToken: (t: string) => { sessionStorage.setItem('mindsafe_refresh', t); },
}));

import Login from '../pages/Login';

// antd Button 在中文间自动插入空格（autoInsertSpaceInButton）→ 「登录」渲染为「登 录」；
// 6 字按钮（企业微信登录）不插入空格；但其 icon 的 aria-label 参与 accessible name，故用文本查询
const loginBtn = () => screen.getByRole('button', { name: /^登\s*录$/ });
const wecomBtn = () => screen.queryByText('企业微信登录');

describe('Login 登录页', () => {
  beforeEach(() => {
    mockCallEndpoint.mockReset();
    sessionStorage.clear();
  });

  it('渲染登录表单与标题', () => {
    mockCallEndpoint.mockResolvedValue({ enabled: false });
    render(<Login onLogin={vi.fn()} />);
    expect(screen.getByText('MindSafe 教师工作台')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/用户名/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('密码')).toBeInTheDocument();
    expect(loginBtn()).toBeInTheDocument();
  });

  it('企业微信未启用时不展示企业微信按钮', async () => {
    mockCallEndpoint.mockResolvedValue({ enabled: false });
    render(<Login onLogin={vi.fn()} />);
    await waitFor(() => expect(mockCallEndpoint).toHaveBeenCalledWith('getWecomAuthUrl', undefined));
    expect(wecomBtn()).not.toBeInTheDocument();
  });

  it('企业微信启用时展示登录按钮并跳转', async () => {
    mockCallEndpoint.mockResolvedValue({ enabled: true, authUrl: 'https://wecom.example.com/oauth' });
    // jsdom 不支持 location.href 赋值导航，替换为普通可写对象
    Object.defineProperty(window, 'location', {
      value: { href: 'http://localhost:3000/' },
      writable: true,
    });
    render(<Login onLogin={vi.fn()} />);
    const btn = await screen.findByText('企业微信登录');
    fireEvent.click(btn);
    expect(window.location.href).toBe('https://wecom.example.com/oauth');
  });

  it('提交成功：写入 token 并回调 onLogin', async () => {
    mockCallEndpoint.mockImplementation((key: string) => {
      if (key === 'getWecomAuthUrl') return Promise.resolve({ enabled: false });
      return Promise.resolve({
        token: 'tk-1', refreshToken: 'rt-1', userId: 'u-1',
        userType: 'psych_teacher', displayName: '李老师', mustChangePassword: false,
      });
    });
    const onLogin = vi.fn();
    render(<Login onLogin={onLogin} />);

    fireEvent.change(screen.getByPlaceholderText(/用户名/), { target: { value: 'teacher1' } });
    fireEvent.change(screen.getByPlaceholderText('密码'), { target: { value: 'pass1234' } });
    fireEvent.click(loginBtn());

    await waitFor(() => expect(onLogin).toHaveBeenCalledWith({
      userId: 'u-1', userType: 'psych_teacher', displayName: '李老师', mustChangePassword: false,
    }));
    expect(sessionStorage.getItem('mindsafe_token')).toBe('tk-1');
    expect(sessionStorage.getItem('mindsafe_refresh')).toBe('rt-1');
    expect(mockCallEndpoint).toHaveBeenCalledWith('login', expect.objectContaining({
      body: JSON.stringify({ username: 'teacher1', password: 'pass1234' }),
    }));
  });

  it('提交失败：不回调 onLogin', async () => {
    mockCallEndpoint.mockImplementation((key: string) => {
      if (key === 'getWecomAuthUrl') return Promise.resolve({ enabled: false });
      return Promise.reject(new Error('用户名或密码错误'));
    });
    const onLogin = vi.fn();
    render(<Login onLogin={onLogin} />);

    fireEvent.change(screen.getByPlaceholderText(/用户名/), { target: { value: 'bad' } });
    fireEvent.change(screen.getByPlaceholderText('密码'), { target: { value: 'wrong' } });
    fireEvent.click(loginBtn());

    await waitFor(() => expect(onLogin).not.toHaveBeenCalled());
    expect(sessionStorage.getItem('mindsafe_token')).toBeNull();
  });

  // DOC-086 / BUG-T-BASE-01 回归：用户名 + 密码 input 必须具备 a11y 自动填充语义
  it('用户名/密码 input 应具备 a11y 自动填充属性', () => {
    mockCallEndpoint.mockResolvedValue({ enabled: false });
    render(<Login onLogin={vi.fn()} />);
    const userInput = screen.getByPlaceholderText(/用户名/) as HTMLInputElement;
    const pwdInput = screen.getByPlaceholderText('密码') as HTMLInputElement;
    // antd 5+ 会透传 autoComplete 到 input
    expect(userInput.autocomplete).toBe('username');
    expect(pwdInput.autocomplete).toBe('current-password');
  });

  // DOC-086 / BUG-T-BASE-02 回归：企业微信未配置不应产生控制台 error 噪声，降级为 debug
  it('企业微信获取失败时仅 console.debug，不产生 error 噪声', async () => {
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    // 预置但未返回的 reject；mockImplementation 需先 reset，再覆盖 getWecomAuthUrl 路径
    mockCallEndpoint.mockImplementation((key: string) => {
      if (key === 'getWecomAuthUrl') return Promise.reject(new Error('企业微信未配置'));
      return Promise.reject(new Error('login not used'));
    });
    render(<Login onLogin={vi.fn()} />);
    // 等 useEffect 内的 catch 执行
    await waitFor(() => expect(mockCallEndpoint).toHaveBeenCalledWith('getWecomAuthUrl', undefined));
    // BUG-T-BASE-02 核心：catch 路径不应产生 error；只允许 debug
    expect(errorSpy).not.toHaveBeenCalled();
    expect(debugSpy).toHaveBeenCalled();
    debugSpy.mockRestore();
    errorSpy.mockRestore();
  });
});
