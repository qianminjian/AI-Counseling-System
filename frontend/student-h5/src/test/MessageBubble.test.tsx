import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import MessageBubble from '../components/MessageBubble'
import { emotionEmoji } from '../../../shared/src/emotionMeta'

// mock emotionTypography
vi.mock('../theme/emotionTypography', () => ({
  getEmotionTypo: (emotion) => ({
    scale: 1.0,
    weight: 400,
    accent: '#0EA5E9',
    tint: '#F0F9FF',
    anim: 'anim-fade-in',
  }),
}))

describe('MessageBubble', () => {
  const defaultProps = {
    msg: { role: 'assistant' as const, content: '你好呀', emotion: 'happy' },
    isLast: false,
    streaming: false,
    onReplay: vi.fn(),
    isSpeaking: false,
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('emotionEmoji 映射（F4 收编 shared emotionMeta）', () => {
    it('包含基本情绪', () => {
      expect(emotionEmoji('happy')).toBe('😊')
      expect(emotionEmoji('sad')).toBe('😢')
      expect(emotionEmoji('angry')).toBe('😠')
      expect(emotionEmoji('fearful')).toBe('😨')
      expect(emotionEmoji('neutral')).toBe('😐')
    })

    it('unknown 和 other 为空字符串', () => {
      expect(emotionEmoji('unknown')).toBe('')
      expect(emotionEmoji('other')).toBe('')
    })
  })

  describe('AI 消息渲染', () => {
    it('显示 AI 回复内容', () => {
      render(<MessageBubble {...defaultProps} />)
      expect(screen.getByText('你好呀')).toBeTruthy()
    })

    it('非流式时显示播放按钮', () => {
      render(<MessageBubble {...defaultProps} />)
      expect(screen.getByTitle('播放语音')).toBeTruthy()
    })

    it('点击播放按钮触发 onReplay', () => {
      const onReplay = vi.fn()
      render(<MessageBubble {...defaultProps} onReplay={onReplay} />)
      fireEvent.click(screen.getByTitle('播放语音'))
      expect(onReplay).toHaveBeenCalledWith('你好呀')
    })

    it('streaming 时不显示播放按钮', () => {
      render(<MessageBubble {...defaultProps} streaming={true} />)
      expect(screen.queryByTitle('播放语音')).toBeNull()
    })

    it('isSpeaking 时播放按钮显示动画状态', () => {
      const { container } = render(<MessageBubble {...defaultProps} isSpeaking={true} />)
      // 播放中按钮有 animate-pulse 子元素
      expect(container.querySelector('.animate-pulse')).toBeTruthy()
    })
  })

  describe('用户消息渲染', () => {
    it('右对齐显示用户消息', () => {
      const { container } = render(
        <MessageBubble {...defaultProps} msg={{ role: 'user', content: '我不开心' }} />
      )
      expect(container.querySelector('.justify-end')).toBeTruthy()
    })

    it('用户消息无播放按钮', () => {
      render(
        <MessageBubble {...defaultProps} msg={{ role: 'user', content: '我不开心' }} />
      )
      expect(screen.queryByTitle('播放语音')).toBeNull()
    })

    it('用户消息带情绪 emoji', () => {
      render(
        <MessageBubble
          {...defaultProps}
          msg={{ role: 'user' as const, content: '我生气了', emotion: 'angry' }}
        />
      )
      expect(screen.getByText('😠')).toBeTruthy()
    })

    it('unknown 情绪不显示 emoji', () => {
      const { container } = render(
        <MessageBubble
          {...defaultProps}
          msg={{ role: 'user' as const, content: '嗯', emotion: 'unknown' }}
        />
      )
      // 不应有 emoji span
      expect(container.querySelector('.opacity-80')).toBeNull()
    })
  })

  describe('系统消息渲染', () => {
    it('居中显示系统消息', () => {
      const { container } = render(
        <MessageBubble {...defaultProps} msg={{ role: 'system', content: '注意', level: 2 }} />
      )
      expect(container.querySelector('.text-center')).toBeTruthy()
      expect(screen.getByText('注意')).toBeTruthy()
    })

    it('level>=3 使用红色样式', () => {
      const { container } = render(
        <MessageBubble {...defaultProps} msg={{ role: 'system', content: '警告', level: 3 }} />
      )
      expect(container.querySelector('.bg-red-50')).toBeTruthy()
    })

    it('level<3 使用琥珀色样式', () => {
      const { container } = render(
        <MessageBubble {...defaultProps} msg={{ role: 'system', content: '提示', level: 1 }} />
      )
      expect(container.querySelector('.bg-amber-50')).toBeTruthy()
    })
  })

  describe('流式占位', () => {
    it('streaming 且 isLast 且无内容时显示省略号', () => {
      render(
        <MessageBubble
          {...defaultProps}
          msg={{ role: 'assistant', content: '' }}
          streaming={true}
          isLast={true}
        />
      )
      expect(screen.getByText('...')).toBeTruthy()
    })
  })
})
