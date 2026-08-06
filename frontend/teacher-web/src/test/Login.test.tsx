import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 登录页测试（ARCH-009 E-2 补测）
 * - 渲染用户名/密码表单
 * - 企业微信登录链接：enabled=true 时展示，否则不展示
 * - 表单提交成功 → setToken/setRefreshToken + onLogin
 * - 提交失败 → 不调用 onLogin（message.error 兜底）
 */

const mockApi = vi.fn();
vi.mock('../api', () => ({
  api: (path: string, options?: unknown) => mockApi(path, options),
  setToken: (t: string) => { localStorage.setItem('mindsafe_token', t); },
  setRefreshToken: (t: string) => { localStorage.setItem('mindsafe_refresh', t); },
}));

import Login from '../pages/Login';

// antd Button 在中文间自动插入空格（autoInsertSpaceInButton）→ 「登录」渲染为「登 录」；
// 6 字按钮（企业微信登录）不插入空格；但其 icon 的 aria-label 参与 accessible name，故用文本查询
const loginBtn = () => screen.getByRole('button', { name: /^登\s*录$/ });
const wecomBtn = () => screen.queryByText('企业微信登录');

describe('Login 登录页', () => {
  beforeEach(() => {
    mockApi.mockReset();
    localStorage.clear();
  });

  it('渲染登录表单与标题', () => {
    mockApi.mockResolvedValue({ enabled: false });
    render(<Login onLogin={vi.fn()} />);
    expect(screen.getByText('MindSafe 教师工作台')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/用户名/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('密码')).toBeInTheDocument();
    expect(loginBtn()).toBeInTheDocument();
  });

  it('企业微信未启用时不展示企业微信按钮', async () => {
    mockApi.mockResolvedValue({ enabled: false });
    render(<Login onLogin={vi.fn()} />);
    await waitFor(() => expect(mockApi).toHaveBeenCalledWith('/auth/wecom/auth-url', undefined));
    expect(wecomBtn()).not.toBeInTheDocument();
  });

  it('企业微信启用时展示登录按钮并跳转', async () => {
    mockApi.mockResolvedValue({ enabled: true, authUrl: 'https://wecom.example.com/oauth' });
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
    mockApi.mockImplementation((path: string) => {
      if (path === '/auth/wecom/auth-url') return Promise.resolve({ enabled: false });
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
    expect(localStorage.getItem('mindsafe_token')).toBe('tk-1');
    expect(localStorage.getItem('mindsafe_refresh')).toBe('rt-1');
    expect(mockApi).toHaveBeenCalledWith('/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it('提交失败：不回调 onLogin', async () => {
    mockApi.mockImplementation((path: string) => {
      if (path === '/auth/wecom/auth-url') return Promise.resolve({ enabled: false });
      return Promise.reject(new Error('用户名或密码错误'));
    });
    const onLogin = vi.fn();
    render(<Login onLogin={onLogin} />);

    fireEvent.change(screen.getByPlaceholderText(/用户名/), { target: { value: 'bad' } });
    fireEvent.change(screen.getByPlaceholderText('密码'), { target: { value: 'wrong' } });
    fireEvent.click(loginBtn());

    await waitFor(() => expect(onLogin).not.toHaveBeenCalled());
    expect(localStorage.getItem('mindsafe_token')).toBeNull();
  });
});
