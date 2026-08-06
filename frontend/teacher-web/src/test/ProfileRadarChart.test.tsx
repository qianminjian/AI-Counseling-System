import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

/**
 * 心理画像雷达图测试（PROF-004，ARCH-009 E-2 补测）
 * - loading 态
 * - 无画像（hasProfile=false）→ 空态
 * - 有画像 → 雷达图容器 + 累计会话数 + 成长里程碑
 * - 接口失败 → 空态降级
 */

const mockGetRadar = vi.fn();
const mockSetOption = vi.fn();
const mockResize = vi.fn();
const mockDispose = vi.fn();

vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: mockSetOption, resize: mockResize, dispose: mockDispose })),
}));
vi.mock('../api', () => ({
  getStudentRadar: (id: string) => mockGetRadar(id),
}));

import ProfileRadarChart from '../components/teacher/ProfileRadarChart';

const profileData = {
  hasProfile: true,
  totalSessions: 12,
  dimensions: [
    { name: '情绪稳定', score: 80 },
    { name: '社交能力', score: 65 },
  ],
  milestones: [
    { label: '完成第一次倾诉', period: '2026-06' },
    { label: '情绪表达改善', period: '2026-07' },
  ],
};

describe('ProfileRadarChart 心理画像', () => {
  beforeEach(() => {
    mockGetRadar.mockReset();
    mockSetOption.mockClear();
  });

  it('加载中显示 Spin', () => {
    mockGetRadar.mockReturnValue(new Promise(() => {}));
    render(<ProfileRadarChart studentId="s-1" />);
    expect(screen.getByText('心理画像')).toBeInTheDocument();
  });

  it('无画像时显示空态', async () => {
    mockGetRadar.mockResolvedValue({ hasProfile: false, dimensions: [], totalSessions: 0 });
    render(<ProfileRadarChart studentId="s-1" />);
    expect(await screen.findByText(/暂无画像数据/)).toBeInTheDocument();
  });

  it('有画像时渲染雷达图与里程碑', async () => {
    mockGetRadar.mockResolvedValue(profileData);
    render(<ProfileRadarChart studentId="s-1" />);
    expect(await screen.findByText('累计 12 次会话')).toBeInTheDocument();
    expect(screen.getByText('🏆 成长里程碑')).toBeInTheDocument();
    expect(screen.getByText('完成第一次倾诉')).toBeInTheDocument();
    // React 18 passive effect（useEffect）经 scheduler 异步执行：findByText 返回时
    // setOption 可能尚未运行（慢环境更明显），必须 waitFor 轮询断言
    await waitFor(() => expect(mockSetOption).toHaveBeenCalledWith(expect.objectContaining({
      series: [expect.objectContaining({ type: 'radar' })],
    })));
  });

  it('接口失败时降级为空态', async () => {
    mockGetRadar.mockRejectedValue(new Error('network'));
    render(<ProfileRadarChart studentId="s-1" />);
    expect(await screen.findByText(/暂无画像数据/)).toBeInTheDocument();
  });
});
