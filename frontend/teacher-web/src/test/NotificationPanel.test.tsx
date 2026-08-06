import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 通知中心面板测试（ARCH-009 E-2 补测）
 * - 渲染通知列表（标题/摘要/时间）
 * - 未读通知显示「已读」按钮，点击调用 markNotificationRead 并刷新
 * - 已读通知显示绿色「已读」Tag，无按钮
 */

const mockGetNotifications = vi.fn();
const mockMarkRead = vi.fn();
vi.mock('../api', () => ({
  getNotifications: (limit?: number) => mockGetNotifications(limit),
  markNotificationRead: (id: string) => mockMarkRead(id),
}));

import NotificationPanel from '../components/teacher/NotificationPanel';

const notifications = [
  {
    notificationId: 'n-1', title: '红色预警：小明', bodySummary: '检测到自伤风险信号',
    severity: 3, deliveryStatus: 'unread', createdAt: '2026-07-28T08:00:00',
  },
  {
    notificationId: 'n-2', title: '橙预警：小红', bodySummary: '焦虑情绪持续',
    severity: 2, deliveryStatus: 'read', createdAt: '2026-07-27T10:00:00',
  },
];

describe('NotificationPanel 通知中心', () => {
  beforeEach(() => {
    mockGetNotifications.mockReset().mockResolvedValue(notifications);
    mockMarkRead.mockReset().mockResolvedValue(null);
  });

  it('渲染通知列表', async () => {
    render(<NotificationPanel />);
    expect(await screen.findByText('红色预警：小明')).toBeInTheDocument();
    expect(screen.getByText('检测到自伤风险信号')).toBeInTheDocument();
    expect(screen.getByText('橙预警：小红')).toBeInTheDocument();
  });

  it('未读通知显示已读按钮，已读通知显示 Tag', async () => {
    render(<NotificationPanel />);
    await screen.findByText('红色预警：小明');
    // 未读行按钮 + 已读行 Tag 各一个
    expect(screen.getAllByText('已读')).toHaveLength(2);
  });

  it('点击已读调用 markNotificationRead 并刷新', async () => {
    render(<NotificationPanel />);
    await screen.findByText('红色预警：小明');
    // 第一个「已读」为未读行的操作按钮
    fireEvent.click(screen.getAllByText('已读')[0]);
    await waitFor(() => expect(mockMarkRead).toHaveBeenCalledWith('n-1'));
    // 标记后重新加载列表（加载次数 ≥2）
    await waitFor(() => expect(mockGetNotifications.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('加载失败时显示空态且不崩溃', async () => {
    mockGetNotifications.mockRejectedValue(new Error('network'));
    render(<NotificationPanel />);
    await screen.findByText('暂无通知');
  });
});
