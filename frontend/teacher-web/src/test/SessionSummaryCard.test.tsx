import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

/**
 * 会话 AI 摘要卡片测试（ARCH-009 E-2 补测）
 * - 无 sessionId 不请求
 * - not_found → 空态
 * - pending → 生成中文案
 * - ready + JSON 摘要 → 渲染话题/关键点/风险提示/建议
 * - 非 JSON 摘要 → 原文展示
 */

const mockGetSummary = vi.fn();
vi.mock('../api', () => ({
  getSessionSummary: (id: string) => mockGetSummary(id),
}));

import SessionSummaryCard from '../components/teacher/SessionSummaryCard';

describe('SessionSummaryCard 会话摘要卡片', () => {
  beforeEach(() => {
    mockGetSummary.mockReset();
  });

  it('无 sessionId 时不发起请求', () => {
    render(<SessionSummaryCard sessionId={null as unknown as string} />);
    expect(mockGetSummary).not.toHaveBeenCalled();
  });

  it('not_found 状态显示空态', async () => {
    mockGetSummary.mockResolvedValue({ summary: '', status: 'not_found' });
    render(<SessionSummaryCard sessionId="s-1" />);
    expect(await screen.findByText('暂无会话记录')).toBeInTheDocument();
  });

  it('pending 状态显示生成中文案', async () => {
    mockGetSummary.mockResolvedValue({ summary: '', status: 'pending' });
    render(<SessionSummaryCard sessionId="s-1" />);
    expect(await screen.findByText(/AI 摘要生成中/)).toBeInTheDocument();
  });

  it('ready + JSON 摘要渲染结构化内容', async () => {
    mockGetSummary.mockResolvedValue({
      status: 'ready',
      summary: JSON.stringify({
        mainTopic: '考试焦虑',
        emotionTrend: '焦虑→缓解',
        keyPoints: ['提到考试压力大', '睡眠不足'],
        riskNote: '建议关注',
        suggestion: '安排一次放松对话',
      }),
    });
    render(<SessionSummaryCard sessionId="s-1" />);
    expect(await screen.findByText('AI 会话摘要')).toBeInTheDocument();
    expect(screen.getByText('考试焦虑')).toBeInTheDocument();
    expect(screen.getByText('焦虑→缓解')).toBeInTheDocument();
    expect(screen.getByText('提到考试压力大')).toBeInTheDocument();
    expect(screen.getByText(/风险提示：建议关注/)).toBeInTheDocument();
    expect(screen.getByText('安排一次放松对话')).toBeInTheDocument();
  });

  it('非 JSON 摘要直接展示原文', async () => {
    mockGetSummary.mockResolvedValue({ status: 'ready', summary: '这是一段普通文本摘要' });
    render(<SessionSummaryCard sessionId="s-1" />);
    expect(await screen.findByText('这是一段普通文本摘要')).toBeInTheDocument();
  });

  it('接口失败时显示空态', async () => {
    mockGetSummary.mockRejectedValue(new Error('network'));
    render(<SessionSummaryCard sessionId="s-1" />);
    expect(await screen.findByText('暂无会话记录')).toBeInTheDocument();
    await waitFor(() => expect(mockGetSummary).toHaveBeenCalledWith('s-1'));
  });
});
