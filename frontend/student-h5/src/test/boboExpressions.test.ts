/**
 * 波波表情状态机单测（TTSFX-004，design/37 §4.1）
 *
 * 验收锚点（design/37 §六 M1）：
 *   WHEN AI 回复 emotion=soothe THEN 波波 SHALL 切换到 hug 状态
 *   S0/S1 风险时界面锁定 bobo_hug，不随后续消息切换
 * 状态机规则：事件驱动、可打断、同刻仅一个状态。
 */
import { describe, it, expect } from 'vitest'
import {
  boboExpressionReducer,
  initialExpressionState,
  EMOTION_TO_EXPRESSION,
} from '../utils/boboExpressions'

describe('utils/boboExpressions', () => {
  describe('EMOTION_TO_EXPRESSION 映射（design/37 §4.1 表）', () => {
    it('happy → happy（开心弹跳）', () => {
      expect(EMOTION_TO_EXPRESSION.happy).toBe('happy')
    })
    it('gentle/calm → gentle（轻轻点头）', () => {
      expect(EMOTION_TO_EXPRESSION.gentle).toBe('gentle')
      expect(EMOTION_TO_EXPRESSION.calm).toBe('gentle')
    })
    it('encourage → cheer（举手加油）', () => {
      expect(EMOTION_TO_EXPRESSION.encourage).toBe('cheer')
    })
    it('soothe/serious → hug（靠近抱抱）', () => {
      expect(EMOTION_TO_EXPRESSION.soothe).toBe('hug')
      expect(EMOTION_TO_EXPRESSION.serious).toBe('hug')
    })
  })

  describe('事件驱动切换', () => {
    it('初始状态为 idle', () => {
      expect(initialExpressionState().expression).toBe('idle')
      expect(initialExpressionState().locked).toBe(false)
    })

    it('AI 回复情绪事件驱动表情切换（M1 验收：soothe → hug）', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'reply-emotion', emotion: 'soothe' })
      expect(next.expression).toBe('hug')
    })

    it('学生输入中 → listen（侧耳倾听）', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'typing' })
      expect(next.expression).toBe('listen')
    })

    it('AI 思考中 → think（歪头冒泡泡）', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'thinking' })
      expect(next.expression).toBe('think')
    })

    it('离线 → sleep（打盹）', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'offline' })
      expect(next.expression).toBe('sleep')
    })

    it('idle 事件回落 idle（呼吸感微动）', () => {
      const s1 = boboExpressionReducer(initialExpressionState(), { type: 'reply-emotion', emotion: 'happy' })
      const s2 = boboExpressionReducer(s1, { type: 'idle' })
      expect(s2.expression).toBe('idle')
    })

    it('可打断：新事件覆盖旧状态（同刻仅一个状态）', () => {
      let s = boboExpressionReducer(initialExpressionState(), { type: 'reply-emotion', emotion: 'happy' })
      s = boboExpressionReducer(s, { type: 'typing' })
      expect(s.expression).toBe('listen')
      s = boboExpressionReducer(s, { type: 'reply-emotion', emotion: 'encourage' })
      expect(s.expression).toBe('cheer')
    })
  })

  describe('S0/S1 风险锁定（安全红线）', () => {
    it('risk 事件（S0/S1）锁定 hug', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'risk', riskLevel: 3 })
      expect(next.expression).toBe('hug')
      expect(next.locked).toBe(true)
    })

    it('锁定后普通情绪事件不可切换（M1 验收：S0/S1 锁定不随后续消息切换）', () => {
      let s = boboExpressionReducer(initialExpressionState(), { type: 'risk', riskLevel: 3 })
      s = boboExpressionReducer(s, { type: 'reply-emotion', emotion: 'happy' })
      expect(s.expression).toBe('hug')
      s = boboExpressionReducer(s, { type: 'typing' })
      expect(s.expression).toBe('hug')
      s = boboExpressionReducer(s, { type: 'thinking' })
      expect(s.expression).toBe('hug')
    })

    it('risk-cleared 解锁后恢复事件驱动', () => {
      let s = boboExpressionReducer(initialExpressionState(), { type: 'risk', riskLevel: 2 })
      s = boboExpressionReducer(s, { type: 'risk-cleared' })
      expect(s.locked).toBe(false)
      s = boboExpressionReducer(s, { type: 'reply-emotion', emotion: 'happy' })
      expect(s.expression).toBe('happy')
    })

    it('YELLOW 风险（riskLevel=1）不锁定，走普通安抚 hug', () => {
      const s = boboExpressionReducer(initialExpressionState(), { type: 'risk', riskLevel: 1 })
      expect(s.expression).toBe('hug')
      expect(s.locked).toBe(false)
    })
  })

  describe('未知/异常事件（失败安全）', () => {
    it('未知事件类型返回原状态（不崩溃）', () => {
      const s0 = initialExpressionState()
      const next = boboExpressionReducer(s0, { type: 'nonsense' } as never)
      expect(next).toEqual(s0)
    })

    it('未知情绪标签回落 idle', () => {
      const next = boboExpressionReducer(initialExpressionState(), { type: 'reply-emotion', emotion: 'weird' } as never)
      expect(next.expression).toBe('idle')
    })
  })
})
