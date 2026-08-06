import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { notification } from 'antd';

/**
 * 教师工作台测试（ARCH-009 E-2 补测）
 * - 默认渲染工作台 + 侧边栏菜单（非管理员无平台/管理菜单）
 * - 菜单切换渲染对应面板
 * - 未读数 Badge / 管理员菜单 / 退出 / 暗色切换
 * - 15s 轮询发现新预警 → 提示音 + 页内通知降级
 */

const mockGetUnreadCount = vi.fn();
vi.mock('../api', () => ({
  getUnreadCount: () => mockGetUnreadCount(),
}));
vi.mock('../hooks/useAlertWebSocket', () => ({
  useAlertWebSocket: () => null,
}));

// 子面板打桩（各自单独测试）
vi.mock('../components/teacher/OverviewPanel', () => ({ default: () => <div>工作台桩</div> }));
vi.mock('../components/teacher/AlertQueue', () => ({ default: () => <div>预警队列桩</div> }));
vi.mock('../components/teacher/StudentPanel', () => ({ default: () => <div>学生管理桩</div> }));
vi.mock('../components/teacher/NotificationPanel', () => ({ default: () => <div>通知中心桩</div> }));
vi.mock('../components/teacher/AdminPanel', () => ({ default: () => <div>管理控制台桩</div> }));
vi.mock('../components/teacher/PlatformPanel', () => ({ default: () => <div>平台总览桩</div> }));
vi.mock('../components/teacher/QualityPanel', () => ({ default: () => <div>质量监控桩</div> }));
vi.mock('../components/teacher/OnboardingGuide', () => ({ default: () => null }));

import Dashboard from '../pages/Dashboard';

const teacher = { userType: 'teacher', displayName: '王老师' };
const admin = { userType: 'admin', displayName: '管理员' };

/** AudioContext 桩（playAlertSound 使用） */
class FakeAudioContext {
  currentTime = 0;
  destination = {};
  createOscillator() {
    return { connect: vi.fn(), frequency: {}, type: '', start: vi.fn(), stop: vi.fn() };
  }
  createGain() {
    return { connect: vi.fn(), gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() } };
  }
}

describe('Dashboard 教师工作台', () => {
  beforeEach(() => {
    mockGetUnreadCount.mockReset().mockResolvedValue(0);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('默认渲染工作台与侧边栏菜单（非管理员无平台/管理菜单）', async () => {
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    expect(await screen.findByText('工作台桩')).toBeInTheDocument();
    // 「工作台」同时出现在菜单项与 Header 标题
    expect(screen.getAllByText('工作台').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('预警队列')).toBeInTheDocument();
    expect(screen.getByText('学生管理')).toBeInTheDocument();
    expect(screen.getByText('质量监控')).toBeInTheDocument();
    expect(screen.getByText('通知中心')).toBeInTheDocument();
    expect(screen.queryByText('平台总览')).not.toBeInTheDocument();
    expect(screen.queryByText('管理控制台')).not.toBeInTheDocument();
  });

  it('点击菜单切换面板', async () => {
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await screen.findByText('工作台桩');
    fireEvent.click(screen.getByText('预警队列'));
    expect(await screen.findByText('预警队列桩')).toBeInTheDocument();
    fireEvent.click(screen.getByText('学生管理'));
    expect(await screen.findByText('学生管理桩')).toBeInTheDocument();
    fireEvent.click(screen.getByText('通知中心'));
    expect(await screen.findByText('通知中心桩')).toBeInTheDocument();
  });

  it('未读数渲染 Badge 角标', async () => {
    mockGetUnreadCount.mockResolvedValue(3);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await screen.findByText('工作台桩');
    await waitFor(() => {
      const badge = document.querySelector('.ant-badge-count');
      expect(badge?.textContent).toContain('3');
    });
  });

  it('管理员可见平台总览与管理控制台并可切换', async () => {
    render(<Dashboard user={admin} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await screen.findByText('工作台桩');
    expect(screen.getByText('平台总览')).toBeInTheDocument();
    expect(screen.getByText('管理控制台')).toBeInTheDocument();
    fireEvent.click(screen.getByText('平台总览'));
    expect(await screen.findByText('平台总览桩')).toBeInTheDocument();
  });

  it('退出与暗色切换回调', async () => {
    const onLogout = vi.fn();
    const toggleDark = vi.fn();
    render(<Dashboard user={teacher} onLogout={onLogout} darkMode={false} toggleDark={toggleDark} />);
    await screen.findByText('工作台桩');
    fireEvent.click(screen.getByText('退出'));
    expect(onLogout).toHaveBeenCalled();
    fireEvent.click(screen.getByTitle('切换暗色'));
    expect(toggleDark).toHaveBeenCalled();
  });

  it('15s 轮询发现新预警时播放提示音并降级页内通知', async () => {
    vi.useFakeTimers();
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never);
    vi.stubGlobal('AudioContext', FakeAudioContext);
    // 首轮探测返回 0，轮询返回 2 → 触发新预警通知
    mockGetUnreadCount.mockResolvedValueOnce(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(1);
    mockGetUnreadCount.mockResolvedValue(2);
    act(() => { vi.advanceTimersByTime(15000); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(2);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    warnSpy.mockRestore();
  });
});
