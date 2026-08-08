import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 数据大屏测试（P1-FE-2：大屏恒 0 根因修复）
 * 契约：指标卡字段名与后端 TeacherService.DashboardVO 一一对齐
 * - 待处理预警 = pendingAlerts（曾误用不存在的 openAlerts → 恒 0）
 * - 活跃学生 = activeStudents / 累计会话 = totalSessions（后端新增字段）
 * - 今日会话 = todaySessions / 平均满意度 = SatisfactionStatsVO.avgRating
 */

const mockGetStats = vi.fn();
const mockGetDashboard = vi.fn();
const mockGetSatisfaction = vi.fn();

vi.mock('../api', () => ({
  getStats: () => mockGetStats(),
  getDashboard: () => mockGetDashboard(),
  getSatisfaction: () => mockGetSatisfaction(),
}));

import BigScreen from '../pages/BigScreen';

const statsMock = {
  sessionTrend: [
    { date: '2026-07-26', count: 1 },
    { date: '2026-07-27', count: 7 },
    { date: '2026-07-28', count: 3 },
  ],
  emotionDistribution: [
    { emotion: 'happy', count: 10 },
    { emotion: 'sad', count: 12 },
  ],
  riskDistribution: [
    { level: 0, label: '绿色', count: 4 },
    { level: 2, label: '橙色', count: 6 },
  ],
  classComparison: [
    { classCode: '301班', alertCount: 3, studentCount: 30 },
    { classCode: '302班', alertCount: 1, studentCount: 28 },
  ],
};

const dashboardMock = {
  pendingAlerts: 2,
  todayAlerts: 4,
  todaySessions: 8,
  activeStudents: 5,
  totalSessions: 50,
  weeklyTrend: [{ date: '2026-07-28', count: 8 }],
  avgSatisfaction: 4.5,
  satisfactionCount: 3,
};

describe('BigScreen 数据大屏', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetStats.mockResolvedValue(statsMock);
    mockGetDashboard.mockResolvedValue(dashboardMock);
    mockGetSatisfaction.mockResolvedValue({ totalRated: 3, avgRating: 4.5, distribution: [], recentCount: 2, recentAvg: 4.0 });
  });

  it('指标卡渲染 DashboardVO 字段值，不再恒 0', async () => {
    render(<BigScreen />);

    // 今日会话 8 / 活跃学生 5 / 待处理预警 2 / 累计会话 50 / 平均满意度 4.5
    expect(await screen.findByText('8')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('50')).toBeInTheDocument();
    expect(screen.getByText('4.5')).toBeInTheDocument();
  });

  it('图表区渲染 stats 数据（情绪/风险/班级对比）', async () => {
    render(<BigScreen />);

    expect(await screen.findByText('开心')).toBeInTheDocument();
    expect(screen.getByText('难过')).toBeInTheDocument();
    expect(screen.getByText('绿色')).toBeInTheDocument();
    expect(screen.getByText('橙色')).toBeInTheDocument();
    expect(screen.getByText('301班')).toBeInTheDocument();
    expect(screen.getByText('302班')).toBeInTheDocument();
  });

  it('接口失败/空数据时兜底渲染 0 与 -，不抛异常', async () => {
    mockGetStats.mockResolvedValue({});
    mockGetDashboard.mockResolvedValue({});
    mockGetSatisfaction.mockResolvedValue({});
    render(<BigScreen />);

    // 空数据 → 4 个指标卡兜底 0，满意度兜底 '-'
    expect(await screen.findAllByText('0')).not.toHaveLength(0);
    expect(screen.getByText('-')).toBeInTheDocument();
  });

  it('接口全部失败时显示错误提示与重试按钮（P2-13 不静默）', async () => {
    mockGetStats.mockRejectedValue(new Error('网络错误'));
    mockGetDashboard.mockRejectedValue(new Error('网络错误'));
    mockGetSatisfaction.mockRejectedValue(new Error('网络错误'));
    render(<BigScreen />);

    // 错误条 + 重试入口（不静默）
    expect(await screen.findByText(/数据加载失败/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument();
  });

  it('点击重试 → 数据恢复 → 错误提示消失', async () => {
    mockGetStats.mockRejectedValueOnce(new Error('网络错误'));
    mockGetDashboard.mockRejectedValueOnce(new Error('网络错误'));
    mockGetSatisfaction.mockRejectedValueOnce(new Error('网络错误'));
    render(<BigScreen />);

    expect(await screen.findByText(/数据加载失败/)).toBeInTheDocument();

    // 恢复后点击重试
    mockGetStats.mockResolvedValue(statsMock);
    mockGetDashboard.mockResolvedValue(dashboardMock);
    mockGetSatisfaction.mockResolvedValue({ totalRated: 3, avgRating: 4.5, distribution: [], recentCount: 2, recentAvg: 4.0 });
    await userEvent.click(screen.getByRole('button', { name: /重试/ }));

    // 错误消失 + 数据出现
    expect(screen.queryByText(/数据加载失败/)).not.toBeInTheDocument();
    expect(await screen.findByText('8')).toBeInTheDocument();
  });
});
