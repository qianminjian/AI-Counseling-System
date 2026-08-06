import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

/**
 * 新手引导弹窗测试（ARCH-009 E-2 补测）
 * - 首次访问（localStorage 无标记）显示引导 Modal
 * - 已完成引导（localStorage 有标记）不显示
 * - 下一步/上一步导航
 * - 最后一步"开始使用"写入标记并关闭
 * - 跳过同样写入标记
 */

import OnboardingGuide from '../components/teacher/OnboardingGuide';

const KEY = 'mindsafe_onboarding_done';

describe('OnboardingGuide 新手引导', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('首次访问显示引导弹窗', () => {
    render(<OnboardingGuide />);
    expect(screen.getByText('工作台')).toBeInTheDocument();
    expect(screen.getByText('下一步')).toBeInTheDocument();
  });

  it('已完成引导不显示', () => {
    localStorage.setItem(KEY, 'true');
    render(<OnboardingGuide />);
    expect(screen.queryByText('下一步')).not.toBeInTheDocument();
  });

  it('下一步可导航到后续步骤并显示上一步', () => {
    render(<OnboardingGuide />);
    fireEvent.click(screen.getByText('下一步'));
    expect(screen.getByText('预警队列')).toBeInTheDocument();
    expect(screen.getByText('上一步')).toBeInTheDocument();
  });

  it('上一步可返回前一步', () => {
    render(<OnboardingGuide />);
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('上一步'));
    expect(screen.getByText('工作台')).toBeInTheDocument();
  });

  it('走完最后一步写入标记并关闭', () => {
    render(<OnboardingGuide />);
    // 5 步：1 工作台 → 2 预警队列 → 3 学生管理 → 4 实时通知 → 5 管理控制台
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('下一步'));
    expect(screen.getByText('管理控制台')).toBeInTheDocument();
    fireEvent.click(screen.getByText('开始使用 🎉'));
    expect(localStorage.getItem(KEY)).toBe('true');
  });

  it('点击跳过写入标记并关闭', () => {
    render(<OnboardingGuide />);
    fireEvent.click(screen.getByText('跳过'));
    expect(localStorage.getItem(KEY)).toBe('true');
  });
});
