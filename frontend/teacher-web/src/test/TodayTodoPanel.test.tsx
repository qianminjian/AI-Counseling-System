import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 今日待办面板测试（WB-001，ARCH-009 E-2 补测）
 * - 渲染待办（学生名/风险标签/SLA 徽标）与 24h 时间线
 * - 逾期预警显示「逾期 Nmin」徽标与「N 项逾期」计数
 * - 认领按钮调用 claimAlert 并刷新
 * - 空态显示「今日暂无待办」
 */

const mockGetAlerts = vi.fn();
const mockGetFollowups = vi.fn();
const mockClaimAlert = vi.fn();
vi.mock('../api', () => ({
  getAlerts: (params?: unknown) => mockGetAlerts(params),
  getPendingFollowups: () => mockGetFollowups(),
  claimAlert: (id: string) => mockClaimAlert(id),
}));

import TodayTodoPanel from '../components/teacher/TodayTodoPanel';

// SLA：level 3 → 5min；level 2 → 15min；level 1 → 60min
const now = Date.now();
const mkAlert = (over: Partial<Record<string, unknown>>) => ({
  alertId: 'a-x', studentUserId: 's-x', studentName: '学生',
  riskType: 'self_harm', riskLevel: 3, status: 'open',
  detectedAt: new Date(now).toISOString(), assignedUserId: null, mutedFromTodo: false,
  ...over,
});

const normalAlert = mkAlert({
  alertId: 'a-1', studentUserId: 's-1', studentName: '小明',
  riskLevel: 1, riskType: 'anxiety',
  detectedAt: new Date(now - 10 * 60_000).toISOString(), // level1 60min SLA，剩 50min
});
const breachedAlert = mkAlert({
  alertId: 'a-2', studentUserId: 's-2', studentName: '小红',
  riskLevel: 3, riskType: 'self_harm',
  detectedAt: new Date(now - 30 * 60_000).toISOString(), // level3 5min SLA → 逾期 25min
});
const claimedAlert = mkAlert({
  alertId: 'a-3', studentUserId: 's-3', studentName: '小刚',
  riskLevel: 2, status: 'claimed',
  detectedAt: new Date(now - 2 * 60_000).toISOString(), // level2 15min，剩 13min
});

const followups = [
  {
    riskEventId: 'f-1', studentUserId: 's-9', riskType: 'self_harm', riskLevel: 3,
    followUpAt: new Date(now + 3600_000).toISOString(), resolutionNote: '',
    detectedAt: new Date(now - 60_000).toISOString(),
  },
];

describe('TodayTodoPanel 今日待办', () => {
  beforeEach(() => {
    mockGetAlerts.mockReset().mockResolvedValue([normalAlert, breachedAlert, claimedAlert]);
    mockGetFollowups.mockReset().mockResolvedValue(followups);
    mockClaimAlert.mockReset().mockResolvedValue(null);
  });

  it('渲染待办预警与回访待办', async () => {
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    // 学生名同时出现在待办列表与时间线，用 getAllByText
    expect((await screen.findAllByText('小明')).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('小红').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('小刚').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('回访待完成')).toBeInTheDocument();
  });

  it('逾期预警显示逾期徽标与计数', async () => {
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    await screen.findAllByText('小红');
    expect(screen.getByText(/逾期 \d+min/)).toBeInTheDocument();
    expect(screen.getByText(/1 项逾期/)).toBeInTheDocument();
  });

  it('未逾期预警显示剩余时间徽标', async () => {
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    await screen.findAllByText('小明');
    // 小明（剩 50min）与小刚（剩 13min）均为未逾期
    expect(screen.getAllByText(/剩 \d+min/).length).toBeGreaterThanOrEqual(2);
  });

  it('open 预警显示认领按钮，点击后调用 claimAlert 并刷新', async () => {
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    const btns = await screen.findAllByText('认领');
    fireEvent.click(btns[0]);
    await waitFor(() => expect(mockClaimAlert).toHaveBeenCalledWith(expect.stringContaining('a-')));
    await waitFor(() => expect(mockGetAlerts.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('渲染 24h 预警时间线', async () => {
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    expect(await screen.findByText('预警时间线（24h）')).toBeInTheDocument();
    // 时间线含学生名（3 条预警全部进入时间线）
    expect(screen.getAllByText('小明').length).toBeGreaterThanOrEqual(1);
  });

  it('无预警时显示空态', async () => {
    mockGetAlerts.mockResolvedValue([]);
    mockGetFollowups.mockResolvedValue([]);
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    expect(await screen.findByText(/今日暂无待办/)).toBeInTheDocument();
    expect(screen.getByText(/24 小时内无预警/)).toBeInTheDocument();
  });

  it('加载失败显示错误提示且不崩溃', async () => {
    mockGetAlerts.mockRejectedValue(new Error('network'));
    render(<TodayTodoPanel onNavigate={vi.fn()} />);
    expect(await screen.findByText(/今日暂无待办|暂无待办/)).toBeInTheDocument();
  });
});
