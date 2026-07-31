import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

// Mock 依赖模块
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({ theme: { companion: '🐻', primary: '#FF6B6B' }, themeId: 'ocean' }),
}));
vi.mock('../api', () => ({ api: vi.fn(), getUser: vi.fn(() => null) }));
vi.mock('../utils/audioUnlock', () => ({ unlockAudio: vi.fn() }));
vi.mock('../components/SceneDecor', () => ({ default: () => <div data-testid="scene-decor" /> }));
vi.mock('../components/SettingsPanel', () => ({ default: () => null }));
vi.mock('../components/ConfirmDialog', () => ({ default: () => null }));
vi.mock('../components/RelaxationExercises', () => ({ default: ({ onBack }) => <div data-testid="relaxation">放松练习</div> }));
vi.mock('../components/EmotionDiary', () => ({ default: ({ onBack }) => <div data-testid="diary">心情日记</div> }));
vi.mock('../components/Achievements', () => ({ default: () => <div data-testid="achievements" /> }));

import EmotionSelect from '../components/EmotionSelect';

describe('EmotionSelect', () => {
  const defaultProps = {
    onStart: vi.fn(),
    userName: '小明',
    onLogout: vi.fn(),
  };

  it('显示用户名称问候', () => {
    render(<EmotionSelect {...defaultProps} />);
    expect(screen.getByText(/嗨，小明！/)).toBeInTheDocument();
  });

  it('未选择情绪时开始按钮禁用', () => {
    render(<EmotionSelect {...defaultProps} />);
    const startBtn = screen.getByText('开始聊天 💬');
    expect(startBtn).toBeDisabled();
  });

  it('渲染 5 个情绪选项', () => {
    render(<EmotionSelect {...defaultProps} />);
    expect(screen.getByText('开心')).toBeInTheDocument();
    expect(screen.getByText('难过')).toBeInTheDocument();
    expect(screen.getByText('生气')).toBeInTheDocument();
    expect(screen.getByText('害怕')).toBeInTheDocument();
    expect(screen.getByText('紧张')).toBeInTheDocument();
  });

  it('选择情绪后开始按钮启用', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('开心'));
    const startBtn = screen.getByText('开始聊天 💬');
    expect(startBtn).not.toBeDisabled();
  });

  it('点击放松练习切换到放松页面', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('放松练习'));
    expect(screen.getByTestId('relaxation')).toBeInTheDocument();
  });

  it('点击心情日记切换到日记页面', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('心情日记'));
    expect(screen.getByTestId('diary')).toBeInTheDocument();
  });

  it('切换同学入口已移至顶栏（与设置并排显示）', () => {
    render(<EmotionSelect {...defaultProps} />);
    expect(screen.getByText('换人')).toBeInTheDocument();
  });

  it('userName 为空时显示"同学"', () => {
    render(<EmotionSelect {...defaultProps} userName="" />);
    expect(screen.getByText(/嗨，同学！/)).toBeInTheDocument();
  });
});
