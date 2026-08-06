import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 教师工作台总览面板测试（ARCH-009 E-2 补测）
 * - loading 态 → 数据态
 * - 渲染 4 张统计卡（待处理预警/今日新增/活跃会话/满意度）
 * - 高风险学生列表
 * - 周报导出按钮触发 openWeeklyReport
 * - 满意度卡片（totalRated>0 显示，否则不显示）
 */

const mockGetDashboard = vi.fn();
const mockGetHighRisk = vi.fn();
const mockGetStats = vi.fn();
const mockGetSatisfaction = vi.fn();
const mockOpenWeeklyReport = vi.fn();
vi.mock('../api', () => ({
  getDashboard: () => mockGetDashboard(),
  getHighRiskStudents: () => mockGetHighRisk(),
  getStats: () => mockGetStats(),
  getSatisfaction: () => mockGetSatisfaction(),
  openWeeklyReport: () => mockOpenWeeklyReport(),
}));

// 子组件打桩：TodayTodoPanel（自身已单独测试）+ 图表（echarts 已单独测试）
vi.mock('../components/teacher/TodayTodoPanel', () => ({
  default: ({ onNavigate }: { onNavigate: (k: string) => void }) => (
    <div onClick={() => onNavigate('alerts')}>今日待办桩</div>
  ),
}));
vi.mock('../components/teacher/StatsCharts', () => ({
  SessionTrendChart: () => <div>趋势图桩</div>,
  RiskPieChart: () => <div>风险图桩</div>,
  ClassBarChart: () => <div>班级图桩</div>,
  EmotionBarChart: () => <div>情绪图桩</div>,
}));

import OverviewPanel from '../components/teacher/OverviewPanel';

const dashboard = {
  pendingAlerts: 2, todayAlerts: 5, todaySessions: 30, activeStudents: 200,
  totalSessions: 1200, weeklyTrend: [{ date: '2026-07-01', count: 3 }],
  avgSatisfaction: 4.2, satisfactionCount: 50,
};

const highRisk = [
  { studentUserId: 's-1', displayName: '小明', gradeCode: '5', maxRiskLevel: 3, openAlertCount: 2, lastAlertAt: '2026-07-28T08:00:00' },
  { studentUserId: 's-2', displayName: '小红', gradeCode: '6', maxRiskLevel: 2, openAlertCount: 1, lastAlertAt: '2026-07-28T07:00:00' },
];

const stats = {
  riskDistribution: [{ level: 3, label: '高', count: 1 }],
  classComparison: [{ classCode: '5-1', alertCount: 2, studentCount: 30 }],
  sessionTrend: [{ date: '2026-07-01', count: 3 }],
  emotionDistribution: [{ emotion: 'happy', count: 10 }],
};

describe('OverviewPanel 工作台总览', () => {
  beforeEach(() => {
    mockGetDashboard.mockReset();
    mockGetHighRisk.mockReset();
    mockGetStats.mockReset();
    mockGetSatisfaction.mockReset();
    mockOpenWeeklyReport.mockReset();
    mockGetDashboard.mockResolvedValue(dashboard);
    mockGetHighRisk.mockResolvedValue(highRisk);
    mockGetStats.mockResolvedValue(stats);
    mockGetSatisfaction.mockResolvedValue({
      totalRated: 50, avgRating: 4.2, recentCount: 10, recentAvg: 4.5,
      distribution: [{ stars: 5, count: 30 }, { stars: 4, count: 20 }],
    });
  });

  it('渲染统计卡与高风险学生', async () => {
    render(<OverviewPanel onNavigate={vi.fn()} />);
    expect(await screen.findByText('待处理预警')).toBeInTheDocument();
    expect(screen.getByText('今日新增预警')).toBeInTheDocument();
    expect(screen.getByText('今日活跃会话')).toBeInTheDocument();
    expect(screen.getByText('近30天平均满意度')).toBeInTheDocument();
    expect(screen.getByText('小明')).toBeInTheDocument();
    expect(screen.getByText('小红')).toBeInTheDocument();
    expect(screen.getByText('2 条预警')).toBeInTheDocument();
  });

  it('统计卡数值渲染', async () => {
    render(<OverviewPanel onNavigate={vi.fn()} />);
    await screen.findByText('待处理预警');
    // antd Statistic 数值渲染在 .ant-statistic-content-value-int 内（4.2 可能拆分为 4 与 .2）
    const values = Array.from(document.querySelectorAll('.ant-statistic-content-value-int, .ant-statistic-content-value-decimal'))
      .map((el) => el.textContent ?? '')
      .join('');
    expect(values).toContain('2'); // 待处理预警
    expect(values).toContain('5'); // 今日新增
    expect(values).toContain('30'); // 今日活跃会话
    expect(values).toContain('4.2'); // 满意度
    expect(screen.getByText('50 条评价')).toBeInTheDocument();
  });

  it('点击周报导出触发 openWeeklyReport', async () => {
    render(<OverviewPanel onNavigate={vi.fn()} />);
    await screen.findByText('待处理预警');
    fireEvent.click(screen.getByText('导出周报（可打印 PDF）'));
    expect(mockOpenWeeklyReport).toHaveBeenCalled();
  });

  it('无高风险学生显示空态文案', async () => {
    mockGetHighRisk.mockResolvedValue([]);
    render(<OverviewPanel onNavigate={vi.fn()} />);
    expect(await screen.findByText(/暂无高风险学生/)).toBeInTheDocument();
  });

  it('满意度无数据时不渲染满意度卡片', async () => {
    mockGetSatisfaction.mockResolvedValue({ totalRated: 0 });
    render(<OverviewPanel onNavigate={vi.fn()} />);
    await screen.findByText('待处理预警');
    expect(screen.queryByText('学生满意度')).not.toBeInTheDocument();
  });

  it('接口失败时显示错误态并结束加载（AUD-019）', async () => {
    mockGetDashboard.mockRejectedValue(new Error('network'));
    mockGetStats.mockRejectedValue(new Error('network'));
    render(<OverviewPanel onNavigate={vi.fn()} />);
    // AUD-019：失败渲染错误 Alert + 重试按钮，而非静默 fail-open
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('工作台数据加载失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重新加载/ })).toBeInTheDocument();
    expect(screen.queryByText('今日待办桩')).not.toBeInTheDocument();
  });
});
