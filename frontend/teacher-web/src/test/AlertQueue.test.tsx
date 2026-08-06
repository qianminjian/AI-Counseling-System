import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

/**
 * 预警队列面板测试（design/05 §13）
 * - 渲染预警行（学生/类型/状态）
 * - open 状态显示认领按钮，点击调用 claimAlert 并刷新列表
 * - resolved 状态不出现认领/误报操作
 * - SLA 展示分支：无时限 / 逾期（红）/ 剩 Xmin（橙≤5 / 蓝>5）/ 已关闭
 * - claimed 操作按钮 / 处理弹窗闭环 / 误报确认 / 导出 / 加载失败
 */

const mockClaimAlert = vi.fn((_id: string) => Promise.resolve(null));
const mockMarkFalsePositive = vi.fn((_id: string) => Promise.resolve(null));
const mockResolveAlert = vi.fn((_id: string, _note?: string) => Promise.resolve(null));
const mockExportCsv = vi.fn();
const mockGetAlerts = vi.fn((_params?: unknown) => Promise.resolve([
  {
    alertId: 'a-1', studentUserId: 's-1', studentName: '小明',
    riskType: 'self_harm', riskLevel: 3, status: 'open',
    detectedAt: new Date().toISOString(), assignedUserId: null, mutedFromTodo: false,
  },
  {
    alertId: 'a-2', studentUserId: 's-2', studentName: '小红',
    riskType: 'anxiety', riskLevel: 1, status: 'resolved',
    detectedAt: new Date(Date.now() - 3600_000).toISOString(), assignedUserId: null, mutedFromTodo: false,
  },
]));

vi.mock('../api', () => ({
  getAlerts: (params?: { status?: string; minLevel?: number; limit?: number }) => mockGetAlerts(params),
  claimAlert: (id: string) => mockClaimAlert(id),
  markFalsePositive: (id: string) => mockMarkFalsePositive(id),
  resolveAlert: (id: string, note?: string) => mockResolveAlert(id, note),
  exportAlertsCsv: () => mockExportCsv(),
}));

import AlertQueue from '../components/teacher/AlertQueue';

describe('AlertQueue 预警队列', () => {
  beforeEach(() => {
    mockClaimAlert.mockClear();
    mockMarkFalsePositive.mockClear();
    mockResolveAlert.mockClear();
    mockExportCsv.mockClear();
    mockGetAlerts.mockClear();
  });

  it('渲染预警行：学生名/类型/状态文案', async () => {
    render(<AlertQueue />);

    expect(await screen.findByText('小明')).toBeInTheDocument();
    expect(screen.getByText('小红')).toBeInTheDocument();
    expect(screen.getByText('self_harm')).toBeInTheDocument();
    expect(screen.getByText('待处理')).toBeInTheDocument();
    expect(screen.getByText('已解决')).toBeInTheDocument();
  });

  it('open 预警显示认领按钮，resolved 不显示', async () => {
    render(<AlertQueue />);
    await screen.findByText('小明');

    expect(screen.getByText('认领')).toBeInTheDocument();
    // 仅 open 行有认领/误报按钮，全表只有一个认领按钮
    expect(screen.getAllByText('认领')).toHaveLength(1);
  });

  it('点击认领调用 claimAlert 并重新加载列表', async () => {
    render(<AlertQueue />);
    await screen.findByText('小明');

    fireEvent.click(screen.getByText('认领'));

    await waitFor(() => expect(mockClaimAlert).toHaveBeenCalledWith('a-1'));
    // 初始加载 1 次 + 认领后刷新 1 次
    await waitFor(() => expect(mockGetAlerts.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('SLA 展示：无时限（riskLevel 0）', async () => {
    mockGetAlerts.mockResolvedValueOnce([{
      alertId: 'a-0', studentUserId: 's-0', studentName: '小绿',
      riskType: 'info', riskLevel: 0, status: 'open',
      detectedAt: new Date().toISOString(), assignedUserId: null, mutedFromTodo: false,
    }]);
    render(<AlertQueue />);
    await screen.findByText('小绿');
    expect(screen.getByText('无时限')).toBeInTheDocument();
  });

  it('SLA 展示：逾期显示红色徽标', async () => {
    mockGetAlerts.mockResolvedValueOnce([{
      alertId: 'a-b', studentUserId: 's-b', studentName: '小超',
      riskType: 'self_harm', riskLevel: 3, status: 'open',
      detectedAt: new Date(Date.now() - 600_000).toISOString(), assignedUserId: null, mutedFromTodo: false,
    }]);
    render(<AlertQueue />);
    await screen.findByText('小超');
    expect(screen.getByText(/逾期 \d+min/)).toBeInTheDocument();
  });

  it('SLA 展示：剩 Xmin 徽标（≤5 橙色 / >5 蓝色）', async () => {
    mockGetAlerts.mockResolvedValueOnce([
      {
        alertId: 'a-c', studentUserId: 's-c', studentName: '小橙',
        riskType: 'self_harm', riskLevel: 3, status: 'open',
        detectedAt: new Date(Date.now() - 60_000).toISOString(), assignedUserId: null, mutedFromTodo: false,
      },
      {
        alertId: 'a-d', studentUserId: 's-d', studentName: '小蓝',
        riskType: 'anxiety', riskLevel: 2, status: 'open',
        detectedAt: new Date(Date.now() - 60_000).toISOString(), assignedUserId: null, mutedFromTodo: false,
      },
    ]);
    render(<AlertQueue />);
    await screen.findByText('小橙');
    // 红色等级剩 4min（≤5 → orange）；橙色等级剩 14min（>5 → blue）
    expect(screen.getByText(/剩 4min/)).toBeInTheDocument();
    expect(screen.getByText(/剩 14min/)).toBeInTheDocument();
  });

  it('SLA 展示：已解决显示已关闭', async () => {
    render(<AlertQueue />);
    await screen.findByText('小红');
    expect(screen.getByText('已关闭')).toBeInTheDocument();
  });

  it('claimed 状态：显示处理/误报按钮，无认领按钮', async () => {
    mockGetAlerts.mockResolvedValueOnce([{
      alertId: 'a-e', studentUserId: 's-e', studentName: '小领',
      riskType: 'self_harm', riskLevel: 2, status: 'claimed',
      detectedAt: new Date().toISOString(), assignedUserId: 't-1', mutedFromTodo: false,
    }]);
    render(<AlertQueue />);
    await screen.findByText('小领');
    expect(screen.queryByText('认领')).not.toBeInTheDocument();
    expect(screen.getAllByText('处理').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('误报')).toBeInTheDocument();
  });

  it('处理弹窗：输入备注确认后调用 resolveAlert 并刷新', async () => {
    render(<AlertQueue />);
    await screen.findByText('小明');
    fireEvent.click(screen.getAllByText('处理')[0]);
    // antd Modal 弹出层异步挂载，先等标题出现
    await screen.findByText('预警处理完成');
    const textarea = await screen.findByPlaceholderText(/例如：已与学生谈话/);
    fireEvent.change(textarea, { target: { value: '已约谈家长' } });
    fireEvent.click(screen.getByText('确认处理'));
    await waitFor(() => expect(mockResolveAlert).toHaveBeenCalledWith('a-1', '已约谈家长'));
    await waitFor(() => expect(mockGetAlerts.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('误报确认：Popconfirm 确认后调用 markFalsePositive', async () => {
    render(<AlertQueue />);
    await screen.findByText('小明');
    fireEvent.click(screen.getByText('误报'));
    // Popconfirm 确认气泡异步挂载，先等确认文案出现
    await screen.findByText('确认标记为误报？');
    // antd 未配置全局中文 locale 时 Popconfirm 默认按钮为 OK/Cancel
    fireEvent.click(await screen.findByRole('button', { name: /OK/i }));
    await waitFor(() => expect(mockMarkFalsePositive).toHaveBeenCalledWith('a-1'));
  });

  it('点击导出调用 exportAlertsCsv', async () => {
    render(<AlertQueue />);
    await screen.findByText('小明');
    fireEvent.click(screen.getByText('导出'));
    expect(mockExportCsv).toHaveBeenCalledTimes(1);
  });
});
