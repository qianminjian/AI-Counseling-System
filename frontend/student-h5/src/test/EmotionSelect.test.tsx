import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock 依赖模块
let mockThemeId = 'ocean'
vi.mock('../theme/ThemeProvider', () => ({
  useTheme: () => ({ theme: { companion: '🐻', primary: '#FF6B6B' }, themeId: mockThemeId }),
}));
const mockApi = vi.fn();
vi.mock('../api', () => ({ api: (...args: any[]) => mockApi(...args), getUser: vi.fn(() => null) }));
vi.mock('../utils/audioUnlock', () => ({ unlockAudio: vi.fn() }));
vi.mock('../components/SceneDecor', () => ({ default: () => <div data-testid="scene-decor" /> }));
vi.mock('../components/SettingsPanel', () => ({ default: ({ open, onClose }: any) => open ? <div data-testid="settings"><button onClick={onClose}>关闭设置</button></div> : null }));
vi.mock('../components/ConfirmDialog', () => ({ default: ({ open, onConfirm, onCancel }: any) => open ? <div data-testid="confirm"><button onClick={onConfirm}>确认退出</button><button onClick={onCancel}>取消</button></div> : null }));
vi.mock('../components/RelaxationExercises', () => ({ default: ({ onBack }) => <div data-testid="relaxation"><button onClick={onBack}>返回</button></div> }));
vi.mock('../components/EmotionDiary', () => ({ default: ({ onBack }) => <div data-testid="diary"><button onClick={onBack}>返回</button></div> }));
vi.mock('../components/Achievements', () => ({ default: () => <div data-testid="achievements" /> }));

import EmotionSelect from '../components/EmotionSelect';

describe('EmotionSelect', () => {
  const defaultProps = {
    onStart: vi.fn(),
    userName: '小明',
    onLogout: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

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

  it('选择情绪后点击开始聊天成功调用 onStart', async () => {
    mockApi.mockResolvedValue({ sessionId: 's1', greeting: '你好呀' });
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('开心'));
    fireEvent.click(screen.getByText('开始聊天 💬'));
    await waitFor(() => {
      expect(defaultProps.onStart).toHaveBeenCalledWith({
        sessionId: 's1',
        greeting: '你好呀',
        emotionTag: 'happy',
      });
    });
    expect(mockApi).toHaveBeenCalledWith('/chat/sessions', expect.objectContaining({ method: 'POST' }));
  });

  it('创建会话失败显示错误信息', async () => {
    mockApi.mockRejectedValue(new Error('网络异常'));
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('难过'));
    fireEvent.click(screen.getByText('开始聊天 💬'));
    await waitFor(() => {
      expect(screen.getByText('网络异常')).toBeInTheDocument();
    });
  });

  it('未选择情绪时 handleStart 不触发 API', () => {
    render(<EmotionSelect {...defaultProps} />);
    // 按钮 disabled，不会触发
    const btn = screen.getByText('开始聊天 💬');
    fireEvent.click(btn);
    expect(mockApi).not.toHaveBeenCalled();
  });

  it('garden 主题下渲染放松/日记按钮（覆盖分支）', () => {
    mockThemeId = 'garden';
    render(<EmotionSelect {...defaultProps} />);
    expect(screen.getByText('放松练习')).toBeInTheDocument();
    expect(screen.getByText('心情日记')).toBeInTheDocument();
    mockThemeId = 'ocean';
  });

  it('点击换人弹出确认框并确认退出', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('换人'));
    expect(screen.getByTestId('confirm')).toBeInTheDocument();
    fireEvent.click(screen.getByText('确认退出'));
    expect(defaultProps.onLogout).toHaveBeenCalled();
  });

  it('点击换人后取消', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('换人'));
    fireEvent.click(screen.getByText('取消'));
    expect(screen.queryByTestId('confirm')).toBeNull();
  });

  it('设置面板打开关闭', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('⚙️'));
    expect(screen.getByTestId('settings')).toBeInTheDocument();
    fireEvent.click(screen.getByText('关闭设置'));
    expect(screen.queryByTestId('settings')).toBeNull();
  });

  it('放松练习页面返回', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('放松练习'));
    expect(screen.getByTestId('relaxation')).toBeInTheDocument();
    fireEvent.click(screen.getByText('返回'));
    expect(screen.queryByTestId('relaxation')).toBeNull();
  });

  it('心情日记页面返回', () => {
    render(<EmotionSelect {...defaultProps} />);
    fireEvent.click(screen.getByText('心情日记'));
    expect(screen.getByTestId('diary')).toBeInTheDocument();
    fireEvent.click(screen.getByText('返回'));
    expect(screen.queryByTestId('diary')).toBeNull();
  });
});
