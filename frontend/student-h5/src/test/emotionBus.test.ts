/**
 * emotionBus 单例事件源单测（TTSFX-004，design/37 §三.1/§五）
 *
 * 契约：AI 回复的 emotion 标签由单一事件源分发给三个消费方
 * （TTS 播放器 / BoBoPet 表情状态机 / 主题层），避免三方各取各的信号导致不同步。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { emotionBus, REPLY_EMOTIONS } from '../utils/emotionBus'

describe('utils/emotionBus', () => {
  beforeEach(() => {
    emotionBus.reset()
  })

  describe('REPLY_EMOTIONS 契约', () => {
    it('包含 design/37 §三.1 定义的 6 类回复情绪标签', () => {
      expect(REPLY_EMOTIONS).toEqual([
        'happy', 'gentle', 'encourage', 'calm', 'serious', 'soothe',
      ])
    })
  })

  describe('publish / subscribe', () => {
    it('publish 后所有订阅者收到 emotion', () => {
      const a = vi.fn()
      const b = vi.fn()
      emotionBus.subscribe(a)
      emotionBus.subscribe(b)

      emotionBus.publish('happy')

      expect(a).toHaveBeenCalledWith('happy')
      expect(b).toHaveBeenCalledWith('happy')
    })

    it('publish 记录最新值，后订阅者可通过 current() 取到', () => {
      emotionBus.publish('soothe')
      expect(emotionBus.current()).toBe('soothe')

      const late = vi.fn()
      emotionBus.subscribe(late)
      // 后订阅者不补发（避免组件挂载时误触历史状态），但 current 可读
      expect(late).not.toHaveBeenCalled()
    })

    it('未知标签归一化为 null（不崩溃、不污染消费方）', () => {
      const spy = vi.fn()
      emotionBus.subscribe(spy)

      emotionBus.publish('not-a-real-emotion' as never)

      expect(spy).toHaveBeenCalledWith(null)
      expect(emotionBus.current()).toBeNull()
    })

    it('大小写归一化（后端标签偶发大写）', () => {
      const spy = vi.fn()
      emotionBus.subscribe(spy)
      emotionBus.publish('HAPPY' as never)
      expect(spy).toHaveBeenCalledWith('happy')
    })

    it('unsubscribe 后不再收到事件', () => {
      const spy = vi.fn()
      const unsub = emotionBus.subscribe(spy)
      unsub()
      emotionBus.publish('calm')
      expect(spy).not.toHaveBeenCalled()
    })

    it('单个订阅者抛异常不影响其他订阅者（失败隔离）', () => {
      const bad = vi.fn(() => { throw new Error('boom') })
      const good = vi.fn()
      emotionBus.subscribe(bad)
      emotionBus.subscribe(good)

      expect(() => emotionBus.publish('gentle')).not.toThrow()
      expect(good).toHaveBeenCalledWith('gentle')
    })
  })

  describe('reset', () => {
    it('reset 清空订阅与当前值', () => {
      const spy = vi.fn()
      emotionBus.subscribe(spy)
      emotionBus.publish('happy')
      emotionBus.reset()

      expect(emotionBus.current()).toBeNull()
      emotionBus.publish('calm')
      expect(spy).toHaveBeenCalledTimes(1) // 仅 reset 前那次
    })
  })
})
