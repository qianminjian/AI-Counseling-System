/**
 * doing/94 R-001：student-h5 端点常量表测试（对齐 teacher-web FA-15 模式）
 *
 * 验证三件事：
 * 1. ENDPOINTS 覆盖原 apiContract.test.ts 硬编码清单的全部 23 端点（单一事实源迁移无遗漏）
 * 2. FRONTEND_ENDPOINTS 派生正确（占位符剥离、方法小写）
 * 3. fillPath 路径参数替换行为
 *
 * 注：LEGACY_ENDPOINTS 为一次性迁移守卫（防漏登/漂移），收敛稳定后（1-2 个迭代）应删除，
 * 避免重新引入双维护耦合。
 */
import { describe, expect, it } from 'vitest'
import { ENDPOINTS, FRONTEND_ENDPOINTS, fillPath } from '../endpoints'

/** 原 apiContract.test.ts 硬编码清单（23 端点，迁移基准——一次性守卫，稳定后删除） */
const LEGACY_ENDPOINTS: Array<[path: string, method: string]> = [
  ['/api/v1/auth/trial/register', 'post'],
  ['/api/v1/auth/pin-login', 'post'],
  ['/api/v1/auth/refresh', 'post'],
  ['/api/v1/auth/set-pin', 'post'],
  ['/api/v1/auth/voice-credential', 'post'],
  ['/api/v1/auth/voice-login', 'post'],
  ['/api/v1/auth/guardian-consent/request', 'post'],
  ['/api/v1/auth/guardian-consent/confirm', 'post'],
  ['/api/v1/voiceprint/config', 'get'],
  ['/api/v1/voiceprint/verify', 'post'],
  ['/api/v1/voiceprint/enroll', 'post'],
  ['/api/v1/toolbox', 'get'],
  ['/api/v1/toolbox/sos', 'get'],
  ['/api/v1/toolbox/mood-check', 'post'],
  ['/api/v1/sos/events', 'post'],
  ['/api/v1/chat/sessions', 'post'],
  ['/api/v1/chat/sessions/{sessionId}/messages', 'post'],
  ['/api/v1/chat/sessions/{sessionId}/nudge', 'post'],
  ['/api/v1/sessions/{id}/close', 'post'],
  ['/api/v1/voice/analyze', 'post'],
  ['/api/v1/tts/synthesize', 'post'],
  ['/api/v1/tts/login-prompt', 'post'],
  ['/api/v1/system/config', 'get'],
]

describe('ENDPOINTS 常量表（doing/94 R-001，对齐 teacher-web FA-15）', () => {
  it('覆盖原 apiContract 硬编码清单全部 23 端点（单一事实源迁移无遗漏）', () => {
    const derived = new Map(FRONTEND_ENDPOINTS)
    for (const [path, method] of LEGACY_ENDPOINTS) {
      // 与常量表派生同规则：占位符剥离 + 尾部斜杠剥离（模板端点可留双斜杠，teacher-web 同规 范）
      const expected = path.replace(/\{\w+\}/g, '').replace(/\/+$/, '')
      expect(derived.get(expected), `端点 ${path} 未登记`).toBe(method)
    }
  })

  it('每个登记项 path 以 /api/v1 开头且 method 为小写 HTTP 方法', () => {
    for (const e of Object.values(ENDPOINTS)) {
      expect(e.path.startsWith('/api/v1/')).toBe(true)
      expect(e.method).toMatch(/^(get|post|put|patch|delete)$/)
    }
  })

  it('FRONTEND_ENDPOINTS 由常量表派生且占位符剥离', () => {
    const derived = new Map(FRONTEND_ENDPOINTS)
    // 含占位符端点剥离后登记（双斜杠形态，与 teacher-web 派生规范一致）
    expect(derived.has('/api/v1/chat/sessions//messages')).toBe(true)
    // 派生清单不得残留占位符
    for (const [p] of FRONTEND_ENDPOINTS) {
      expect(p.includes('{'), `派生清单残留占位符: ${p}`).toBe(false)
    }
    // 无占位符端点原样登记
    expect(derived.has('/api/v1/toolbox')).toBe(true)
  })
})

describe('fillPath 路径参数替换', () => {
  it('替换全部 {name} 占位符', () => {
    expect(fillPath('/api/v1/chat/sessions/{sessionId}/messages', { sessionId: 'sess-1' }))
      .toBe('/api/v1/chat/sessions/sess-1/messages')
  })

  it('未提供参数按空串（测试期望，与 teacher-web 一致）', () => {
    expect(fillPath('/api/v1/sessions/{id}/close', {})).toBe('/api/v1/sessions//close')
  })

  it('无占位符模板原样返回', () => {
    expect(fillPath('/api/v1/toolbox', {})).toBe('/api/v1/toolbox')
  })
})
