import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { message, notification } from 'antd';

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
// 捕获 onAlert 回调（vitest mock 工厂仅允许引用 mock 前缀变量）
let mockOnAlertCapture: ((() => void) | null) = null;
vi.mock('../hooks/useAlertWebSocket', () => ({
  useAlertWebSocket: (opts: { onAlert: () => void }) => {
    mockOnAlertCapture = opts.onAlert;
    return null;
  },
}));

// 子面板打桩（各自单独测试）
vi.mock('../components/teacher/OverviewPanel', () => ({ default: () => <div>工作台桩</div> }));
vi.mock('../components/teacher/AlertQueue', () => ({ default: () => <div>预警队列桩</div> }));
vi.mock('../components/teacher/StudentPanel', () => ({ default: () => <div>学生管理桩</div> }));
vi.mock('../components/teacher/NotificationPanel', () => ({ default: () => <div>通知中心桩</div> }));
vi.mock('../components/teacher/AdminPanel', () => ({ default: () => <div>管理控制台桩</div> }));
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

  it('管理员可见管理控制台并可切换（平台总览已迁 admin-web，双轨收敛）', async () => {
    render(<Dashboard user={admin} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await screen.findByText('工作台桩');
    expect(screen.queryByText('平台总览')).not.toBeInTheDocument();
    expect(screen.getByText('管理控制台')).toBeInTheDocument();
    fireEvent.click(screen.getByText('管理控制台'));
    expect(await screen.findByText('管理控制台桩')).toBeInTheDocument();
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

  it('新预警且桌面通知已授权：走 Notification 而非页内降级', async () => {
    vi.useFakeTimers();
    const notifySpy = vi.fn();
    // 用真实 class 桩而非 vi.fn：vi.fn 包装的箭头实现作为构造器（new）调用会抛错，
    // 无法模拟“构造成功”的 granted 路径
    class FakeNotificationClass {
      static permission = 'granted';
      constructor(title: string, opts?: object) {
        notifySpy(title, opts);
      }
    }
    vi.stubGlobal('Notification', FakeNotificationClass);
    vi.stubGlobal('AudioContext', FakeAudioContext);
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never);
    mockGetUnreadCount.mockResolvedValueOnce(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    mockGetUnreadCount.mockResolvedValue(2);
    act(() => { vi.advanceTimersByTime(15000); });
    await act(async () => { await Promise.resolve(); });
    expect(notifySpy).toHaveBeenCalledWith('🛡️ MindSafe 新预警', expect.objectContaining({ body: expect.stringContaining('2 条') }));
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('桌面通知构造器抛错时降级页内通知', async () => {
    vi.useFakeTimers();
    class BoomNotificationClass {
      static permission = 'granted';
      constructor() { throw new Error('NotSupported'); }
    }
    vi.stubGlobal('Notification', BoomNotificationClass);
    vi.stubGlobal('AudioContext', FakeAudioContext);
    const warnSpy = vi.spyOn(notification, 'warning').mockImplementation(() => undefined as never);
    mockGetUnreadCount.mockResolvedValueOnce(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    mockGetUnreadCount.mockResolvedValue(2);
    act(() => { vi.advanceTimersByTime(15000); });
    await act(async () => { await Promise.resolve(); });
    expect(warnSpy).toHaveBeenCalledTimes(1);
    warnSpy.mockRestore();
  });

  it('首次挂载且通知权限为 default 时请求权限', async () => {
    const requestSpy = vi.fn();
    const FakeNotification = vi.fn();
    (FakeNotification as any).permission = 'default';
    (FakeNotification as any).requestPermission = requestSpy;
    vi.stubGlobal('Notification', FakeNotification);
    mockGetUnreadCount.mockResolvedValue(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    expect(requestSpy).toHaveBeenCalled();
  });

  it('窄视口渲染移动端布局，resize 可恢复桌面布局', async () => {
    const original = window.innerWidth;
    try {
      mockGetUnreadCount.mockResolvedValue(0);
      render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
      await screen.findByText('工作台桩');
      // 桌面：侧边栏品牌区 + 姓名可见
      expect(screen.getByText('MindSafe')).toBeInTheDocument();
      expect(screen.getByText('王老师')).toBeInTheDocument();
      // 收窄到 500px → 移动端：无侧边栏/姓名，标题带盾牌，出现底部 Tab 栏
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: 500, writable: true });
      fireEvent(window, new Event('resize'));
      expect(screen.queryByText('MindSafe')).not.toBeInTheDocument();
      expect(screen.queryByText('王老师')).not.toBeInTheDocument();
      expect(screen.getByText('🛡️ 工作台')).toBeInTheDocument();
      expect(screen.getAllByText('预警队列').length).toBeGreaterThanOrEqual(1);
      // 恢复 1024px → 回到桌面
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024, writable: true });
      fireEvent(window, new Event('resize'));
      expect(screen.getByText('MindSafe')).toBeInTheDocument();
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: original, writable: true });
    }
  });

  it('首次探测连续失败后按 2s/5s 自动重试并最终提示', async () => {
    vi.useFakeTimers();
    const warnSpy = vi.spyOn(message, 'warning').mockImplementation(() => undefined as never);
    mockGetUnreadCount.mockRejectedValueOnce(new Error('Failed to fetch'));
    mockGetUnreadCount.mockRejectedValueOnce(new Error('Failed to fetch'));
    mockGetUnreadCount.mockRejectedValueOnce(new Error('Failed to fetch'));
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(2000); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(2);
    act(() => { vi.advanceTimersByTime(5000); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(3);
    expect(warnSpy).toHaveBeenCalledWith('后端服务暂不可达，请确认服务已启动');
    warnSpy.mockRestore();
  });

  it('WebSocket 推送新预警时刷新未读数（成功）', async () => {
    vi.stubGlobal('AudioContext', FakeAudioContext);
    mockGetUnreadCount.mockResolvedValueOnce(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    mockGetUnreadCount.mockResolvedValue(5);
    act(() => { mockOnAlertCapture?.(); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(2);
  });

  it('WebSocket 推送时未读数接口失败静默处理（不弹警告）', async () => {
    const warnSpy = vi.spyOn(message, 'warning').mockImplementation(() => undefined as never);
    mockGetUnreadCount.mockRejectedValue(new Error('Failed to fetch'));
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    act(() => { mockOnAlertCapture?.(); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(warnSpy).not.toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('AudioContext 不可用时提示音静默降级', async () => {
    vi.useFakeTimers();
    class BoomAudioContext {
      constructor() { throw new Error('NotSupported'); }
    }
    vi.stubGlobal('AudioContext', BoomAudioContext);
    mockGetUnreadCount.mockResolvedValueOnce(0);
    render(<Dashboard user={teacher} onLogout={vi.fn()} darkMode={false} toggleDark={vi.fn()} />);
    await act(async () => { await Promise.resolve(); });
    mockGetUnreadCount.mockResolvedValue(2);
    act(() => { vi.advanceTimersByTime(15000); });
    await act(async () => { await Promise.resolve(); });
    expect(mockGetUnreadCount).toHaveBeenCalledTimes(2);
  });
});
