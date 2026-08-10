import { describe, it, expect } from 'vitest'
import { mainHint, subHint, chipHint, VoiceStatusChip, isWakeNotReady } from '../components/VoiceStatusHint'
import { render, screen } from '@testing-library/react'

describe('VoiceStatusHint 状态→文案单一映射（FA-14）', () => {
  describe('mainHint：Pad 左栏主提示', () => {
    it('wakeStatus 优先于 mode（detected/loading/error 直接命中）', () => {
      expect(mainHint({ mode: 'standby', wakeStatus: 'detected' })).toBe('听到了！🎉')
      expect(mainHint({ mode: 'active', wakeStatus: 'loading' })).toBe('语音引擎加载中...')
      expect(mainHint({ mode: 'standby', wakeStatus: 'error' })).toBe('语音引擎未就绪')
    })

    it('mode 分支：standby 提示唤醒词、active 提示直接说', () => {
      expect(mainHint({ mode: 'standby', wakeStatus: 'listening' })).toBe('叫我“哈喽波波”')
      expect(mainHint({ mode: 'active', wakeStatus: 'listening' })).toBe('我在听，直接说吧')
    })

    it('未知状态兜底“想说什么就说什么吧”', () => {
      expect(mainHint({ mode: 'off', wakeStatus: 'idle' })).toBe('想说什么就说什么吧')
    })
  })

  describe('subHint：Pad 左栏副提示', () => {
    it('detected 固定提示', () => {
      expect(subHint({ mode: 'active', wakeStatus: 'detected' })).toBe('正在准备听你说话...')
    })

    it('standby 分支按 wakeStatus 细分（loading/listening/error/其他）', () => {
      // F-29：未就绪文案明确"等会儿再叫我"，避免用户过早呼叫
      expect(subHint({ mode: 'standby', wakeStatus: 'loading' })).toBe('正在加载语音引擎…等会儿再叫我哦')
      expect(subHint({ mode: 'standby', wakeStatus: 'listening' })).toBe('我在这里安静地等你叫我')
      expect(subHint({ mode: 'standby', wakeStatus: 'error' })).toBe('语音引擎加载失败，请关闭再开启')
      // F-23：idle 不得冒充 standby（引擎未就绪时诚实显示准备中）
      expect(subHint({ mode: 'standby', wakeStatus: 'idle' })).toBe('正在准备语音引擎…等会儿再叫我哦')
    })

    it('F-29 isWakeNotReady：idle/loading 判定未就绪，其余非未就绪', () => {
      expect(isWakeNotReady({ mode: 'standby', wakeStatus: 'idle' })).toBe(true)
      expect(isWakeNotReady({ mode: 'standby', wakeStatus: 'loading' })).toBe(true)
      expect(isWakeNotReady({ mode: 'standby', wakeStatus: 'listening' })).toBe(false)
      expect(isWakeNotReady({ mode: 'active', wakeStatus: 'listening' })).toBe(false)
      expect(isWakeNotReady({ mode: 'standby', wakeStatus: 'error' })).toBe(false)
    })

    it('active 与兜底', () => {
      expect(subHint({ mode: 'active', wakeStatus: 'listening' })).toBe('不用按，直接说就行')
      expect(subHint({ mode: 'off', wakeStatus: 'idle' })).toBe('按住波波，跟它说说话')
    })
  })

  describe('chipHint：手机端唤醒状态指示器', () => {
    it('五态映射文案与变体', () => {
      expect(chipHint({ mode: 'standby', wakeStatus: 'loading' })).toEqual({ text: '语音引擎加载中…等会儿再叫我哦', variant: 'loading' })
      expect(chipHint({ mode: 'standby', wakeStatus: 'listening' })).toEqual({ text: '说“哈喽波波”唤醒我', variant: 'listening' })
      expect(chipHint({ mode: 'active', wakeStatus: 'detected' })).toEqual({ text: '🎉 听到了！正在准备听你说话...', variant: 'detected' })
      expect(chipHint({ mode: 'active', wakeStatus: 'listening' })).toEqual({ text: '我在听，直接说吧', variant: 'active' })
      expect(chipHint({ mode: 'active', wakeStatus: 'error' })).toEqual({ text: '语音引擎加载失败，可在设置中重试', variant: 'error' })
    })

    it('无匹配状态（off/idle）返回 null 不渲染', () => {
      expect(chipHint({ mode: 'off', wakeStatus: 'idle' })).toBeNull()
      expect(chipHint({ mode: 'standby', wakeStatus: 'idle' })).toBeNull()
    })

    it('listening 仅在 standby 下展示，active 下落入 active 分支', () => {
      // active + listening：wakeStatus 命中 listening 条件需 mode === 'standby'，否则走 active
      expect(chipHint({ mode: 'standby', wakeStatus: 'listening' })?.variant).toBe('listening')
    })
  })

  describe('VoiceStatusChip 组件', () => {
    it('无匹配状态不渲染', () => {
      const { container } = render(<VoiceStatusChip voiceCall={{ mode: 'off', wakeStatus: 'idle' }} />)
      expect(container.firstChild).toBeNull()
    })

    it('渲染文案与样式变体', () => {
      render(<VoiceStatusChip voiceCall={{ mode: 'standby', wakeStatus: 'loading' }} />)
      const el = screen.getByText('语音引擎加载中…等会儿再叫我哦')
      expect(el.className).toContain('bg-blue-50')
      expect(el.className).toContain('text-blue-500')
    })

    it('active 状态渲染绿色样式', () => {
      render(<VoiceStatusChip voiceCall={{ mode: 'active', wakeStatus: 'listening' }} />)
      const el = screen.getByText('我在听，直接说吧')
      expect(el.className).toContain('bg-green-50')
    })
  })
})
