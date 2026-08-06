import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

/**
 * 平台总览面板测试（仅 ADMIN 可见，ARCH-009 E-2 补测）
 * - 渲染 6 个平台指标
 * - 渲染租户表格（机构名/编码/状态 Tag）
 * - 接口失败时静默降级不崩溃
 */

const mockGetOverview = vi.fn();
const mockGetTenants = vi.fn();
vi.mock('../api', () => ({
  getPlatformOverview: () => mockGetOverview(),
  getPlatformTenants: () => mockGetTenants(),
}));

import PlatformPanel from '../components/teacher/PlatformPanel';

const overview = {
  tenantCount: 3, schoolCount: 5, studentCount: 1200,
  teacherCount: 40, totalSessions: 8800, openAlerts: 2,
};

const tenants = [
  {
    tenantId: 't-1', tenantName: '示范小学', tenantCode: 'DEMO01',
    status: 'active', schoolCount: 2, studentCount: 800, teacherCount: 20,
    sessionCount: 5000, createdAt: '2026-01-01T00:00:00',
  },
  {
    tenantId: 't-2', tenantName: '实验中学', tenantCode: 'EXP02',
    status: 'disabled', schoolCount: 1, studentCount: 400, teacherCount: 10,
    sessionCount: 1200, createdAt: '2026-02-01T00:00:00',
  },
];

describe('PlatformPanel 平台总览', () => {
  beforeEach(() => {
    mockGetOverview.mockReset();
    mockGetTenants.mockReset();
  });

  it('渲染平台指标与租户列表', async () => {
    mockGetOverview.mockResolvedValue(overview);
    mockGetTenants.mockResolvedValue(tenants);
    render(<PlatformPanel />);

    expect(await screen.findByText('合作学校')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('3')).toBeInTheDocument());
    expect(screen.getByText('示范小学')).toBeInTheDocument();
    expect(screen.getByText('实验中学')).toBeInTheDocument();
    expect(screen.getByText('DEMO01')).toBeInTheDocument();
    expect(screen.getByText('active')).toBeInTheDocument();
    expect(screen.getByText('disabled')).toBeInTheDocument();
  });

  it('接口失败时静默降级（空列表不崩溃）', async () => {
    mockGetOverview.mockRejectedValue(new Error('network'));
    mockGetTenants.mockRejectedValue(new Error('network'));
    render(<PlatformPanel />);
    // 加载结束无异常，指标默认 0
    await waitFor(() => expect(screen.getByText('合作学校')).toBeInTheDocument());
    expect(screen.queryByText('示范小学')).not.toBeInTheDocument();
  });
});
