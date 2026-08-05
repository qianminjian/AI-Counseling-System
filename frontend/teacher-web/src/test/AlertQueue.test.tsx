import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

/**
 * 预警队列面板测试（design/05 §13）
 * - 渲染预警行（学生/类型/状态）
 * - open 状态显示认领按钮，点击调用 claimAlert 并刷新列表
 * - resolved 状态不出现认领/误报操作
 */

const mockClaimAlert = vi.fn((_id: string) => Promise.resolve(null));
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
  markFalsePositive: vi.fn(() => Promise.resolve(null)),
  resolveAlert: vi.fn(() => Promise.resolve(null)),
  exportAlertsCsv: vi.fn(),
}));

import AlertQueue from '../components/teacher/AlertQueue';

describe('AlertQueue 预警队列', () => {
  beforeEach(() => {
    mockClaimAlert.mockClear();
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
});
