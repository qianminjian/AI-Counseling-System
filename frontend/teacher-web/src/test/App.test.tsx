import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

/**
 * App 落地页差异化路由接线（F-3，design/35 §3.1）
 *
 * 契约：
 * - 管理者（admin）登录后默认进数据大屏，可点"返回工作台"
 * - 教师角色登录后默认进工作台
 * - 已登录的管理者刷新页面仍落大屏
 */

vi.mock('../pages/Login', () => ({
  default: ({ onLogin }: { onLogin: (u: unknown) => void }) => (
    <button onClick={() => onLogin(loginUser)}>模拟登录</button>
  ),
}));
vi.mock('../pages/Dashboard', () => ({
  default: ({ onLogout, toggleDark }: { onLogout: () => void; toggleDark: () => void }) => (
    <div>
      工作台页面
      <button onClick={onLogout}>退出</button>
      <button onClick={toggleDark}>切暗色</button>
    </div>
  ),
}));
vi.mock('../pages/BigScreen', () => ({
  default: ({ onExit }: { onExit?: () => void }) => (
    <div>
      数据大屏页面
      {onExit && <button onClick={onExit}>返回工作台</button>}
    </div>
  ),
}));
vi.mock('../pages/ChangePassword', () => ({ default: () => <div>改密页面</div> }));
let loginUser: Record<string, unknown> = {};
let mockToken: string | null = null;

vi.mock('../api', () => ({
  getToken: () => mockToken,
  clearToken: () => { mockToken = null; },
}));

import App from '../App';

function makeToken(userType: string) {
  const payload = btoa(JSON.stringify({ sub: 'u-1', userType, displayName: 'tester' }));
  return `header.${payload}.signature`;
}

describe('App 落地页差异化路由', () => {
  beforeEach(() => {
    loginUser = {};
    mockToken = null;
    localStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  it('管理者登录后默认进数据大屏', () => {
    loginUser = { userId: 'u-1', userType: 'admin', displayName: '校长', mustChangePassword: false };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));

    expect(screen.getByText('数据大屏页面')).toBeInTheDocument();
  });

  it('管理者可从大屏返回工作台', () => {
    loginUser = { userId: 'u-1', userType: 'admin', displayName: '校长', mustChangePassword: false };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));
    fireEvent.click(screen.getByText('返回工作台'));

    expect(screen.getByText('工作台页面')).toBeInTheDocument();
  });

  it('心理老师登录后默认进工作台', () => {
    loginUser = { userId: 'u-2', userType: 'psych_teacher', displayName: '李老师', mustChangePassword: false };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));

    expect(screen.getByText('工作台页面')).toBeInTheDocument();
  });

  it('已登录的管理者刷新页面仍落大屏', () => {
    mockToken = makeToken('admin');
    render(<App />);

    expect(screen.getByText('数据大屏页面')).toBeInTheDocument();
  });

  it('已登录的教师刷新页面落工作台', () => {
    mockToken = makeToken('psych_teacher');
    render(<App />);

    expect(screen.getByText('工作台页面')).toBeInTheDocument();
  });

  it('强制改密标记优先于落地页', () => {
    loginUser = { userId: 'u-3', userType: 'admin', displayName: '校长', mustChangePassword: true };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));

    expect(screen.getByText('改密页面')).toBeInTheDocument();
  });

  it('退出登录回到登录页', () => {
    loginUser = { userId: 'u-4', userType: 'teacher', displayName: '王老师', mustChangePassword: false };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));
    fireEvent.click(screen.getByText('退出'));

    expect(screen.getByText('模拟登录')).toBeInTheDocument();
  });

  it('未登录时 /bigscreen 路径不渲染大屏', () => {
    window.history.replaceState({}, '', '/bigscreen');
    render(<App />);

    expect(screen.queryByText('数据大屏页面')).not.toBeInTheDocument();
    expect(screen.getByText('模拟登录')).toBeInTheDocument();
  });

  it('已登录时 /bigscreen 路径直达全屏，返回工作台按钮可退出（BUG-T-BASE-04）', () => {
    mockToken = makeToken('psych_teacher');
    window.history.replaceState({}, '', '/bigscreen');
    render(<App />);

    expect(screen.getByText('数据大屏页面')).toBeInTheDocument();
    // BUG-T-BASE-04：路径模式须有返回按钮（onExit 跳回 /teacher/），原缺失致点击无效
    expect(screen.getByText('返回工作台')).toBeInTheDocument();
  });

  it('暗色模式切换持久化', () => {
    loginUser = { userId: 'u-5', userType: 'teacher', displayName: '赵老师', mustChangePassword: false };
    render(<App />);

    fireEvent.click(screen.getByText('模拟登录'));
    fireEvent.click(screen.getByText('切暗色'));

    expect(localStorage.getItem('mindsafe_dark_mode')).toBe('true');
  });
});
