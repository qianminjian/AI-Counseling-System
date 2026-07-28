import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach } from 'vitest';
import WelcomeGuide from '../components/WelcomeGuide';

describe('WelcomeGuide', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('首次访问显示引导', () => {
    render(<WelcomeGuide />);
    expect(screen.getByText('嗨，欢迎来到心灵小屋！')).toBeInTheDocument();
  });

  it('已完成引导后不再显示', () => {
    localStorage.setItem('mindsafe_welcome_done', 'true');
    const { container } = render(<WelcomeGuide />);
    expect(container.innerHTML).toBe('');
  });

  it('点击下一步切换到第二页', () => {
    render(<WelcomeGuide />);
    fireEvent.click(screen.getByText('下一步'));
    expect(screen.getByText('和波波说说话')).toBeInTheDocument();
  });

  it('最后一页显示"开始使用"', () => {
    render(<WelcomeGuide />);
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('下一步'));
    fireEvent.click(screen.getByText('下一步'));
    expect(screen.getByText('开始使用 🎉')).toBeInTheDocument();
  });

  it('点击跳过关闭引导并写入 localStorage', () => {
    render(<WelcomeGuide />);
    fireEvent.click(screen.getByText('跳过'));
    expect(localStorage.getItem('mindsafe_welcome_done')).toBe('true');
  });

  it('进度点可点击跳转', () => {
    render(<WelcomeGuide />);
    const dots = screen.getAllByRole('button', { hidden: true }).filter(
      el => el.getAttribute('aria-label')?.startsWith('第')
    );
    fireEvent.click(dots[2]);
    expect(screen.getByText('你说的都是安全的')).toBeInTheDocument();
  });

  it('显示滑动提示', () => {
    render(<WelcomeGuide />);
    expect(screen.getByText(/左右滑动也可以翻页/)).toBeInTheDocument();
  });
});
