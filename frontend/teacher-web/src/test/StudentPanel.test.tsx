import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 学生管理面板测试（ARCH-009 E-2 补测）
 * - 渲染学生列表 + 高风险提醒卡片
 * - 点击姓名/高风险 Tag → 档案详情
 * - 添加教师备注 → addStudentNote 并刷新
 * - 对话摘要抽屉（BUG-UI-01）→ 默认不加载逐轮原文，仅展示 AI 摘要
 * - 导出 CSV / 加载失败容错
 */

const mockGetStudents = vi.fn();
const mockGetHighRisk = vi.fn();
const mockGetProfile = vi.fn();
const mockAddNote = vi.fn();
const mockGetMessages = vi.fn();
const mockExportCsv = vi.fn();
vi.mock('../api', () => ({
  getStudents: () => mockGetStudents(),
  getHighRiskStudents: () => mockGetHighRisk(),
  getStudentProfile: (id: string) => mockGetProfile(id),
  addStudentNote: (id: string, content: string) => mockAddNote(id, content),
  getSessionMessages: (sid: string) => mockGetMessages(sid),
  exportStudentsCsv: () => mockExportCsv(),
}));

// 子组件打桩（各自单独测试）
vi.mock('../components/teacher/SessionSummaryCard', () => ({
  default: ({ sessionId }: { sessionId: string }) => <div>摘要桩:{sessionId}</div>,
}));
vi.mock('../components/teacher/ProfileRadarChart', () => ({
  default: ({ studentId }: { studentId: string }) => <div>雷达桩:{studentId}</div>,
}));

import StudentPanel from '../components/teacher/StudentPanel';

const students = [
  { userId: 's-1', displayName: '小明', gradeCode: '5', classCode: '5-1' },
  { userId: 's-2', displayName: '小红', gradeCode: '6', classCode: '6-2' },
];
const highRisk = [
  { studentUserId: 's-1', displayName: '小明', maxRiskLevel: 3 },
];
const profile = {
  displayName: '小明', gradeCode: '5', classCode: '5-1', maxRiskLevel: 3,
  totalSessions: 12,
  recentSessions: [
    { sessionId: 'se-1', startedAt: '2026-07-28T08:00:00', status: 'completed', riskLevel: 2, satisfactionRating: 4 },
  ],
  alertHistory: [{ riskLevel: 3, riskType: 'self_harm', detectedAt: '2026-07-27T08:00:00' }],
  notes: [{ content: '家长已联系', noteType: 'general', createdAt: '2026-07-26T10:00:00' }],
};
const messages = [
  { senderType: 'student', turnCount: 1, emotionLabel: 'sad', riskLevel: 0, contentSummary: '我最近很焦虑' },
  { senderType: 'ai', turnCount: 2, emotionLabel: '', riskLevel: 0, contentSummary: '我们慢慢来' },
];

describe('StudentPanel 学生管理', () => {
  beforeEach(() => {
    mockGetStudents.mockReset().mockResolvedValue(students);
    mockGetHighRisk.mockReset().mockResolvedValue(highRisk);
    mockGetProfile.mockReset().mockResolvedValue(profile);
    mockAddNote.mockReset().mockResolvedValue(null);
    mockGetMessages.mockReset().mockResolvedValue(messages);
    mockExportCsv.mockReset().mockResolvedValue(null);
  });

  it('渲染学生列表与高风险提醒', async () => {
    render(<StudentPanel />);
    expect(await screen.findByText('学生列表')).toBeInTheDocument();
    // 小明同时出现在表格与高风险 Tag
    expect(screen.getAllByText('小明').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('小红')).toBeInTheDocument();
    expect(screen.getByText('⚠️ 高风险学生：')).toBeInTheDocument();
    // 高风险 Tag：列表内姓名旁
    expect(screen.getAllByText('高风险').length).toBeGreaterThanOrEqual(1);
  });

  it('点击学生姓名进入档案详情', async () => {
    render(<StudentPanel />);
    fireEvent.click((await screen.findAllByText('小明'))[0]);
    // 档案详情：返回列表 + 基本信息
    expect(await screen.findByText('返回列表')).toBeInTheDocument();
    expect(mockGetProfile).toHaveBeenCalledWith('s-1');
    expect(screen.getByText('5-1')).toBeInTheDocument(); // 班级
    expect(screen.getByText('12 次')).toBeInTheDocument(); // 累计会话
    expect(screen.getByText('近期会话')).toBeInTheDocument();
    expect(screen.getByText('预警历史')).toBeInTheDocument();
    expect(screen.getByText('家长已联系')).toBeInTheDocument();
    expect(screen.getByText(/雷达桩:s-1/)).toBeInTheDocument();
  });

  it('返回列表按钮回到列表页', async () => {
    render(<StudentPanel />);
    fireEvent.click((await screen.findAllByText('小明'))[0]);
    fireEvent.click(await screen.findByText('返回列表'));
    expect(await screen.findByText('学生列表')).toBeInTheDocument();
  });

  it('添加教师备注调用接口并刷新', async () => {
    render(<StudentPanel />);
    fireEvent.click((await screen.findAllByText('小明'))[0]);
    const textarea = await screen.findByPlaceholderText('添加观察备注...');
    fireEvent.change(textarea, { target: { value: '需要持续关注' } });
    // antd 2 字中文按钮自动插空格 → 「添 加」；icon aria-label 参与 name → 部分匹配
    fireEvent.click(await screen.findByRole('button', { name: /添\s*加/ }));
    await waitFor(() => expect(mockAddNote).toHaveBeenCalledWith('s-1', '需要持续关注'));
    // 刷新：getProfile 被再次调用
    await waitFor(() => expect(mockGetProfile.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('对话摘要抽屉默认不加载原文（BUG-UI-01 摘要隐私）', async () => {
    render(<StudentPanel />);
    fireEvent.click((await screen.findAllByText('小明'))[0]);
    fireEvent.click(await screen.findByText('对话摘要'));
    // 抽屉打开：AI 摘要卡片渲染，但不得请求/展示逐轮原文
    expect(await screen.findByText('摘要桩:se-1')).toBeInTheDocument();
    expect(mockGetMessages).not.toHaveBeenCalled();
    expect(screen.queryByText('我最近很焦虑')).not.toBeInTheDocument();
    expect(screen.queryByText('我们慢慢来')).not.toBeInTheDocument();
  });

  it('导出 CSV 按钮触发接口', async () => {
    render(<StudentPanel />);
    await screen.findByText('学生列表');
    fireEvent.click(screen.getByRole('button', { name: /导出\s*CSV/ }));
    expect(mockExportCsv).toHaveBeenCalled();
  });

  it('加载失败显示错误不崩溃', async () => {
    mockGetStudents.mockRejectedValue(new Error('network'));
    render(<StudentPanel />);
    expect(await screen.findByText('学生列表')).toBeInTheDocument();
  });

  it('档案加载失败显示错误不崩溃', async () => {
    mockGetProfile.mockRejectedValue(new Error('boom'));
    render(<StudentPanel />);
    fireEvent.click((await screen.findAllByText('小明'))[0]);
    // profile 为 null → 组件渲染 Empty（无返回按钮，为组件现有行为）
    expect(await screen.findByText('未找到学生信息')).toBeInTheDocument();
  });
});
