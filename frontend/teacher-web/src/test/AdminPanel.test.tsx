import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 管理面板测试（ARCH-009 E-2 补测）
 * - 邀请码列表渲染（状态/使用情况/过期计算）
 * - 生成 / 停用 / 删除邀请码
 * - CSV 批量导入 → importStudentsCsv + 结果 Alert
 * - 下载模板 / 审计日志
 */

const mockGetInviteCodes = vi.fn();
const mockCreateInviteCode = vi.fn();
const mockDeactivate = vi.fn();
const mockDelete = vi.fn();
const mockImportCsv = vi.fn();
const mockGetAuditLogs = vi.fn();
const mockDownloadTemplate = vi.fn();
vi.mock('../api', () => ({
  getInviteCodes: () => mockGetInviteCodes(),
  createInviteCode: (maxUses: number, expireDays: number) => mockCreateInviteCode(maxUses, expireDays),
  deactivateInviteCode: (codeId: string) => mockDeactivate(codeId),
  deleteInviteCode: (codeId: string) => mockDelete(codeId),
  importStudentsCsv: (file: File) => mockImportCsv(file),
  getAuditLogs: () => mockGetAuditLogs(),
  downloadImportTemplate: () => mockDownloadTemplate(),
}));

import AdminPanel from '../components/teacher/AdminPanel';

const codes = [
  { codeId: 'c-1', code: 'ABC12345', usedCount: 3, maxUses: 10, status: 'active', expiresAt: '2026-08-30T00:00:00', createdAt: '2026-07-01T00:00:00' },
  { codeId: 'c-2', code: 'XYZ67890', usedCount: 0, maxUses: 5, status: 'disabled', expiresAt: null, createdAt: '2026-06-01T00:00:00' },
  // active 但已过期 → 显示「已过期」
  { codeId: 'c-3', code: 'OLD00001', usedCount: 2, maxUses: 20, status: 'active', expiresAt: '2026-01-01T00:00:00', createdAt: '2026-01-01T00:00:00' },
];
const auditLogs = [
  { auditLogId: 'a-1', tenantId: 't-1', userId: 'u-12345678', action: 'CREATE_INVITE_CODE', resourceType: 'INVITE_CODE', detail: '生成邀请码', createdAt: '2026-07-28T09:00:00' },
];

describe('AdminPanel 管理面板', () => {
  beforeEach(() => {
    mockGetInviteCodes.mockReset().mockResolvedValue(codes);
    mockCreateInviteCode.mockReset().mockResolvedValue({ code: 'NEW00001' });
    mockDeactivate.mockReset().mockResolvedValue(null);
    mockDelete.mockReset().mockResolvedValue(null);
    mockImportCsv.mockReset().mockResolvedValue({ created: 2, skipped: 1, errors: [] });
    mockGetAuditLogs.mockReset().mockResolvedValue(auditLogs);
    mockDownloadTemplate.mockReset().mockResolvedValue(null);
  });

  it('渲染邀请码列表（状态/使用情况/过期计算）', async () => {
    render(<AdminPanel />);
    expect(await screen.findByText('试用邀请码管理')).toBeInTheDocument();
    expect(screen.getByText('ABC12345')).toBeInTheDocument();
    expect(screen.getByText('3 / 10')).toBeInTheDocument();
    expect(screen.getByText('0 / 5')).toBeInTheDocument();
    // c-1 active 未过期 → 有效；c-2 disabled → 已停用；c-3 active 已过期 → 已过期
    expect(screen.getAllByText('有效').length).toBe(1);
    expect(screen.getByText('已停用')).toBeInTheDocument();
    expect(screen.getByText('已过期')).toBeInTheDocument();
    expect(screen.getByText('永久')).toBeInTheDocument(); // c-2 无过期时间
  });

  // 超时放宽至 10s：全量 + coverage（v8）模式下资源竞争，Modal 渲染 + antd 动画
  // 在慢环境（CI）可能超过默认 5s（本地单跑约 1-2s，偶发 5.8s 超时）
  it('生成邀请码弹窗流程', async () => {
    render(<AdminPanel />);
    await screen.findByText('试用邀请码管理');
    fireEvent.click(screen.getByRole('button', { name: /生成邀请码/ }));
    expect(screen.getByText('最大使用次数：')).toBeInTheDocument();
    // Modal 确认按钮「生成」→ 2 字中文自动插空格
    fireEvent.click(screen.getByRole('button', { name: /^生\s*成$/ }));
    await waitFor(() => expect(mockCreateInviteCode).toHaveBeenCalledWith(10, 30));
    // 成功后刷新列表
    await waitFor(() => expect(mockGetInviteCodes.mock.calls.length).toBeGreaterThanOrEqual(2));
  }, 10000);

  it('停用邀请码（Popconfirm 确认）', async () => {
    render(<AdminPanel />);
    // 等待表格渲染完成；fixed 列会被 antd 克隆（同一按钮出现 2 份），取第一个
    const stopBtns = await screen.findAllByRole('button', { name: /停\s*用/ });
    fireEvent.click(stopBtns[0]);
    const confirmBtn = await waitFor(() => {
      const btn = document.querySelector('.ant-popconfirm-buttons .ant-btn-primary');
      if (!btn) throw new Error('popconfirm not rendered');
      return btn as Element;
    });
    fireEvent.click(confirmBtn);
    await waitFor(() => expect(mockDeactivate).toHaveBeenCalledWith('c-1'));
  });

  it('删除邀请码（Popconfirm 确认）', async () => {
    render(<AdminPanel />);
    // 等待表格渲染完成；每个邀请码都有删除按钮（fixed 列克隆 → 每行 2 份），取第一个（c-1）
    const delBtns = await screen.findAllByRole('button', { name: /删\s*除/ });
    fireEvent.click(delBtns[0]);
    const confirmBtn = await waitFor(() => {
      const btn = document.querySelector('.ant-popconfirm-buttons .ant-btn-primary');
      if (!btn) throw new Error('popconfirm not rendered');
      return btn as Element;
    });
    fireEvent.click(confirmBtn);
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('c-1'));
  });

  it('CSV 导入调用接口并展示结果 Alert', async () => {
    const { container } = render(<AdminPanel />);
    await screen.findByText('试用邀请码管理');
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).not.toBeNull();
    const file = new File(['小明,5,5-1'], 'students.csv', { type: 'text/csv' });
    fireEvent.change(input, { target: { files: [file] } });
    await waitFor(() => expect(mockImportCsv).toHaveBeenCalledWith(file));
    await waitFor(() => {
      expect(screen.getAllByText(/成功创建 2 人，跳过 1 人/).length).toBeGreaterThanOrEqual(1);
    });
  });

  it('下载模板触发接口', async () => {
    render(<AdminPanel />);
    await screen.findByText('试用邀请码管理');
    fireEvent.click(screen.getByRole('button', { name: /下载模板/ }));
    expect(mockDownloadTemplate).toHaveBeenCalled();
  });

  it('渲染审计日志', async () => {
    render(<AdminPanel />);
    expect(await screen.findByText('操作审计日志')).toBeInTheDocument();
    expect(screen.getByText('CREATE_INVITE_CODE')).toBeInTheDocument();
    expect(screen.getByText('INVITE_CODE')).toBeInTheDocument();
    // D-联动：操作人列展示 userId 前 8 位（与 admin-web AuditPage 同口径）
    expect(screen.getByText('u-123456')).toBeInTheDocument();
  });

  it('邀请码加载失败不崩溃', async () => {
    mockGetInviteCodes.mockRejectedValue(new Error('network'));
    render(<AdminPanel />);
    expect(await screen.findByText('试用邀请码管理')).toBeInTheDocument();
  });

  it('导入失败显示错误不崩溃', async () => {
    mockImportCsv.mockRejectedValue(new Error('parse error'));
    const { container } = render(<AdminPanel />);
    await screen.findByText('试用邀请码管理');
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(['x'], 'a.csv')] } });
    await waitFor(() => expect(mockImportCsv).toHaveBeenCalled());
    expect(screen.getByText('批量导入学生')).toBeInTheDocument();
  });
});
