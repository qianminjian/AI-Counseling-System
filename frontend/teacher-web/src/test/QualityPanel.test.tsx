import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * 质量监控面板测试（P1-FE-3：导出 PDF 张冠李戴修复）
 * 契约：Drawer「导出 PDF」必须导出【当前正在回放】的会话，
 * 曾误用 flagged.find(f => messages.length > 0) 取列表第一条 → 张冠李戴。
 */

const mockGetSessionMessages = vi.fn((_sid: string) => Promise.resolve([
  { senderType: 'student', emotionLabel: 'happy', contentSummary: '今天有点紧张' },
  { senderType: 'ai', emotionLabel: 'calm', contentSummary: '没关系，先深呼吸' },
]));

const mockExportSessionPdf = vi.fn((_sid: string) => Promise.resolve());

vi.mock('../api', () => ({
  getQualityStats: vi.fn(() => Promise.resolve({ avgRating: 3.2, recentAvg: 3.0, flaggedCount: 2, flagRate: 8.5 })),
  getFlaggedSessions: vi.fn(() => Promise.resolve([
    { sessionId: 'sess-A', rating: 1, comment: '回答太生硬', startedAt: '2026-07-28T10:00:00Z' },
    { sessionId: 'sess-B', rating: 2, comment: '没有安抚情绪', startedAt: '2026-07-28T11:00:00Z' },
  ])),
  getSessionMessages: (sid: string) => mockGetSessionMessages(sid),
  exportSessionPdf: (sid: string) => mockExportSessionPdf(sid),
}));

import QualityPanel from '../components/teacher/QualityPanel';

describe('QualityPanel 会话导出', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('回放列表第二条会话后导出，导出的是当前会话而非第一条（不再张冠李戴）', async () => {
    render(<QualityPanel />);
    expect(await screen.findByText('回答太生硬')).toBeInTheDocument();

    // 回放第二条会话（sess-B）
    const replayButtons = screen.getAllByRole('button', { name: /回放/ });
    fireEvent.click(replayButtons[1]);

    // 抽屉加载出 sess-B 的消息
    await waitFor(() => expect(mockGetSessionMessages).toHaveBeenCalledWith('sess-B'));
    expect(await screen.findByText('今天有点紧张')).toBeInTheDocument();

    // 导出 → 必须是 sess-B
    fireEvent.click(screen.getByRole('button', { name: /导出 PDF/ }));
    await waitFor(() => expect(mockExportSessionPdf).toHaveBeenCalledWith('sess-B'));
    expect(mockExportSessionPdf).not.toHaveBeenCalledWith('sess-A');
  });

  it('回放第一条会话后导出的是 sess-A', async () => {
    render(<QualityPanel />);
    await screen.findByText('回答太生硬');

    fireEvent.click(screen.getAllByRole('button', { name: /回放/ })[0]);
    await waitFor(() => expect(mockGetSessionMessages).toHaveBeenCalledWith('sess-A'));

    fireEvent.click(screen.getByRole('button', { name: /导出 PDF/ }));
    await waitFor(() => expect(mockExportSessionPdf).toHaveBeenCalledWith('sess-A'));
  });
});
