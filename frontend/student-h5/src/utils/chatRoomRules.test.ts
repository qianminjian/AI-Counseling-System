/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：波波状态机纯函数
 *
 * 六态全分支：recording > streaming > playing > standby > active > idle（现 ChatRoom L299-304 语义）。
 */
import { describe, it, expect } from 'vitest'
import { computeBoboState, type BoboState } from './chatRoomRules'

describe('computeBoboState', () => {
  it('recording → listening（优先级最高，即使同时在流式/朗读）', () => {
    expect(computeBoboState({ recording: true, streaming: true, playing: true, wakeMode: 'active' })).toBe('listening')
  })

  it('streaming → thinking（无 recording 时）', () => {
    expect(computeBoboState({ recording: false, streaming: true, playing: true, wakeMode: 'active' })).toBe('thinking')
  })

  it('playing → speaking（无 recording/streaming 时）', () => {
    expect(computeBoboState({ recording: false, streaming: false, playing: true, wakeMode: 'standby' })).toBe('speaking')
  })

  it('standby → waitingWake（待唤醒）', () => {
    expect(computeBoboState({ recording: false, streaming: false, playing: false, wakeMode: 'standby' })).toBe('waitingWake')
  })

  it('active → listening（会话窗聆听）', () => {
    expect(computeBoboState({ recording: false, streaming: false, playing: false, wakeMode: 'active' })).toBe('listening')
  })

  it('全关 → idle', () => {
    expect(computeBoboState({ recording: false, streaming: false, playing: false, wakeMode: 'off' })).toBe('idle')
  })

  it('返回类型是 BoboState 合法值', () => {
    // 入参是 wakeMode（三态），输出才是 BoboState（五态）——勿把两者混淆
    const wakeModes: Array<'off' | 'standby' | 'active'> = ['off', 'standby', 'active']
    wakeModes.forEach((wakeMode) => {
      const s = computeBoboState({ recording: false, streaming: false, playing: false, wakeMode })
      expect(['listening', 'thinking', 'speaking', 'waitingWake', 'idle']).toContain(s)
    })
  })
})
