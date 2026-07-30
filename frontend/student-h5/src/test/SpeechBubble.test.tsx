import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import SpeechBubble from '../components/SpeechBubble';

describe('SpeechBubble', () => {
  it('mode 为 null 时不渲染', () => {
    const { container } = render(<SpeechBubble mode={null} text="" />);
    expect(container.innerHTML).toBe('');
  });

  it('thinking 模式显示三个跳动圆点', () => {
    const { container } = render(<SpeechBubble mode="thinking" text="" />);
    const dots = container.querySelectorAll('.animate-bounce');
    expect(dots).toHaveLength(3);
  });

  it('speaking 模式显示文本内容', () => {
    render(<SpeechBubble mode="speaking" text="你好呀，我是波波" />);
    expect(screen.getByText('你好呀，我是波波')).toBeInTheDocument();
  });

  it('listening 模式无文本时显示"正在聆听…"', () => {
    render(<SpeechBubble mode="listening" text="" />);
    expect(screen.getByText('正在聆听…')).toBeInTheDocument();
  });

  it('listening 模式显示实时转写文本', () => {
    render(<SpeechBubble mode="listening" text="我今天不开心" />);
    expect(screen.getByText('我今天不开心')).toBeInTheDocument();
  });

  it('cancelArmed 时显示取消提示（红色）', () => {
    render(<SpeechBubble mode="listening" text="" cancelArmed={true} />);
    expect(screen.getByText('松开手指，取消发送')).toBeInTheDocument();
  });

  it('cancelArmed 时气泡背景为红色', () => {
    const { container } = render(<SpeechBubble mode="listening" text="" cancelArmed={true} />);
    const bubble = container.querySelector('.bg-red-500');
    expect(bubble).not.toBeNull();
  });

  it('align=right 时右对齐', () => {
    const { container } = render(<SpeechBubble mode="speaking" text="test" align="right" />);
    const wrapper = container.firstChild as Element;
    expect(wrapper.className).toContain('right-0');
  });
});
