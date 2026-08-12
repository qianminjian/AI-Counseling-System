/**
 * 会话活动状态互斥表测试（S-015，doing/93）
 *
 * 忙/闲/麦克风单一派生：新增活动源只需扩展 ActivityInput + 本表。
 */
import { describe, expect, it } from 'vitest'
import { deriveActivityState, type ActivityInput } from '../utils/activityState'

function base(over: Partial<ActivityInput> = {}): ActivityInput {
  return {
    streaming: false, ttsPlaying: false, ttsMuted: false,
    recording: false, analyzing: false,
    wakeEnabled: false, hasConsent: false,
    ...over,
  }
}

describe('deriveActivityState 互斥表（S-015）', () => {
  it('全空闲 → 不忙/基础空闲/不要麦', () => {
    const s = deriveActivityState(base())
    expect(s.busy).toBe(false)
    expect(s.idleBase).toBe(true)
    expect(s.micWanted).toBe(false)
  })

  it('任一活动源活跃 → busy（互斥：忙则非空闲）', () => {
    for (const key of ['streaming', 'ttsPlaying', 'recording', 'analyzing'] as const) {
      const s = deriveActivityState(base({ [key]: true }))
      expect(s.busy, `${key} 应使 busy=true`).toBe(true)
      expect(s.idleBase).toBe(false)
    }
  })

  it('静音 → 不空闲（冷场检测互斥）', () => {
    const s = deriveActivityState(base({ ttsMuted: true }))
    expect(s.idleBase).toBe(false)
  })

  it('micWanted 仅由 同意+唤醒 共同决定', () => {
    expect(deriveActivityState(base({ hasConsent: true })).micWanted).toBe(false)
    expect(deriveActivityState(base({ wakeEnabled: true })).micWanted).toBe(false)
    expect(deriveActivityState(base({ hasConsent: true, wakeEnabled: true })).micWanted).toBe(true)
  })

  it('busy 时不因静音/同意变化而改变（活动源独立）', () => {
    const s = deriveActivityState(base({ streaming: true, ttsMuted: true, hasConsent: true, wakeEnabled: true }))
    expect(s.busy).toBe(true)
    expect(s.idleBase).toBe(false)
    expect(s.micWanted).toBe(true)
  })
})
