import { describe, it, expect } from 'vitest'
import { REPLY_EMOTIONS, normalizeReplyEmotion } from './replyEmotion'

describe('shared/replyEmotion AI 回复情绪单一事实源', () => {
  it('包含 design/37 §三.1 定义的 6 类回复情绪标签', () => {
    expect(REPLY_EMOTIONS).toEqual([
      'happy', 'gentle', 'encourage', 'calm', 'serious', 'soothe',
    ])
  })

  it('normalize 小写 + 白名单校验', () => {
    expect(normalizeReplyEmotion('happy')).toBe('happy')
    expect(normalizeReplyEmotion(' GENTLE ')).toBe('gentle')
    expect(normalizeReplyEmotion('sad')).toBeNull() // 孩子情绪不在回复白名单
    expect(normalizeReplyEmotion('unknown')).toBeNull()
    expect(normalizeReplyEmotion(42)).toBeNull()
    expect(normalizeReplyEmotion(null)).toBeNull()
    expect(normalizeReplyEmotion(undefined)).toBeNull()
  })
})
