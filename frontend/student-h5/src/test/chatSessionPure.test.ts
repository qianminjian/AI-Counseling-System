/**
 * doing/94 R-004：sendMessage 编排拆分纯函数测试
 *
 * buildMessageBody：请求体构造（语音情绪分支 / TTS 静音 / 唤醒开关透传）
 * settleStreamEnd：流收尾（endStreaming/stop/bobo idle 三态判定）
 */
import { describe, expect, it, vi } from 'vitest'
import { buildMessageBody, settleStreamEnd } from '../hooks/useChatSession'
import type { ChatTtsLike } from '../hooks/useChatSession'

function makeTts(overrides: Partial<ChatTtsLike> = {}): ChatTtsLike {
  return {
    muted: false,
    stop: vi.fn(),
    unlock: vi.fn(),
    startStreaming: vi.fn(),
    feedToken: vi.fn(),
    endStreaming: vi.fn(),
    ...overrides,
  }
}

describe('buildMessageBody（R-004：请求体构造纯函数）', () => {
  it('手动打字：仅 content + ttsMuted + wakeEnabled', () => {
    expect(buildMessageBody('你好', null, false, true)).toEqual({
      content: '你好',
      ttsMuted: false,
      wakeEnabled: true,
    })
  })

  it('语音自动发送：携带 voiceEmotion 三件套 + inputMode', () => {
    const emotion = { label: '开心', labelEn: 'happy', confidence: 0.9, scores: [0.1, 0.9] }
    expect(buildMessageBody('今天很开心', emotion, true, false)).toEqual({
      content: '今天很开心',
      voiceEmotion: 'happy',
      voiceEmotionConfidence: 0.9,
      inputMode: 'voice',
      ttsMuted: true,
      wakeEnabled: false,
    })
  })
})

describe('settleStreamEnd（R-004：流收尾纯函数）', () => {
  it('muted：不触碰播放器，无内容无情绪 → bobo idle', () => {
    const tts = makeTts({ muted: true })
    const bobo = { dispatch: vi.fn() }
    settleStreamEnd(tts, '', false, bobo)
    expect(tts.endStreaming).not.toHaveBeenCalled()
    expect(tts.stop).not.toHaveBeenCalled()
    expect(bobo.dispatch).toHaveBeenCalledWith({ type: 'idle' })
  })

  it('muted + 已收到情绪标签：bobo 保持情绪表情不回落', () => {
    const tts = makeTts({ muted: true })
    const bobo = { dispatch: vi.fn() }
    settleStreamEnd(tts, '完整回复', true, bobo)
    expect(bobo.dispatch).not.toHaveBeenCalled()
  })

  it('未静音 + 有回复：endStreaming 冲刷缓冲', () => {
    const tts = makeTts()
    settleStreamEnd(tts, '完整回复', false, { dispatch: vi.fn() })
    expect(tts.endStreaming).toHaveBeenCalledTimes(1)
    expect(tts.stop).not.toHaveBeenCalled()
  })

  it('未静音 + 无回复（完全失败）：stop 重置', () => {
    const tts = makeTts()
    settleStreamEnd(tts, '', false, { dispatch: vi.fn() })
    expect(tts.stop).toHaveBeenCalledTimes(1)
    expect(tts.endStreaming).not.toHaveBeenCalled()
  })
})
