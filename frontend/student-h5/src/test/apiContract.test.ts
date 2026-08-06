/**
 * TEST-006/M3：前端契约测试（L3）
 *
 * 数据源：src/__contract__/openapi.json（scripts/gen-openapi-snapshot.sh 从后端 /api-docs 生成）
 * 方向：
 * - 请求方向：api.ts 全部端点 ⊆ 快照 paths + 方法匹配（前端调用不越契约）
 * - 响应方向：测试 mock 样例按快照 schemas 校验（validateMock 反向兼容：
 *   mock 字段 ⊆ schema、类型匹配、无 schema 外字段；Map 容器字段宽容）
 *
 * 快照刷新：后端契约变更后运行 bash scripts/gen-openapi-snapshot.sh [BASE_URL]
 */
import { describe, expect, it } from 'vitest'
import openapiRaw from '../__contract__/openapi.json?raw'
import {
  validateMock,
  type RefResolver,
  type Schema,
} from '../__contract__/schemaValidator'
import {
  loginMock,
  trialMock,
  voiceprintConfigMock,
  toolMock,
  moodCheckMock,
} from './mockFixtures'

/** 契约快照（精简版：info/paths/components.schemas） */
const doc: {
  info: { title: string; version: string }
  paths: Record<string, Record<string, { responses?: Record<string, { content?: Record<string, { schema?: Schema }> }> }>>
  components: { schemas: Record<string, Schema> }
} = JSON.parse(openapiRaw)

const resolveRef: RefResolver = (ref) =>
  doc.components.schemas[ref.replace('#/components/schemas/', '')]

/** 取端点某方法的 200 响应 schema（未声明时 undefined） */
function responseSchema(path: string, method: string): Schema | undefined {
  const op = doc.paths[path]?.[method]
  const content = op?.responses?.['200']?.content
  if (!content) return undefined
  return Object.values(content)[0]?.schema
}

// ===== 前端 api.ts 端点清单（api() 自动拼 /api/v1 前缀；trialRegister/pinLogin 等直连） =====
const FRONTEND_ENDPOINTS: Array<[path: string, method: string]> = [
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
  // ARCH-005 F-3：SSE 会话流 / 语音 / 系统配置（useSseStream / useSilenceNudge / ChatRoom / remote.ts）
  ['/api/v1/chat/sessions', 'post'],
  ['/api/v1/chat/sessions/{sessionId}/messages', 'post'],
  ['/api/v1/chat/sessions/{sessionId}/nudge', 'post'],
  // ARCH-010 D5（OVD-4）：旧关闭接口已下线，前端仅调用 /sessions/{id}/close
  ['/api/v1/sessions/{id}/close', 'post'],
  ['/api/v1/voice/analyze', 'post'],
  ['/api/v1/tts/synthesize', 'post'],
  ['/api/v1/tts/login-prompt', 'post'],
  ['/api/v1/system/config', 'get'],
]

// ===== 响应 mock 样例（单一来源：src/test/mockFixtures.ts，ARCH-005 F-3） =====

describe('契约快照结构', () => {
  it('快照为合法 OpenAPI 精简文档（info/paths/components.schemas）', () => {
    expect(doc.info.title).toContain('MindSafe')
    expect(Object.keys(doc.paths).length).toBeGreaterThan(0)
    expect(Object.keys(doc.components.schemas).length).toBeGreaterThan(0)
  })

  it('学生端 23 个端点全部在快照中', () => {
    for (const [path] of FRONTEND_ENDPOINTS) {
      expect(doc.paths[path], `快照缺少端点 ${path}`).toBeDefined()
    }
  })
})

describe('请求方向：前端端点 ⊆ 契约快照（方法匹配）', () => {
  it.each(FRONTEND_ENDPOINTS)('%s %s', (path, method) => {
    const op = doc.paths[path]?.[method]
    expect(op, `快照缺少 ${path} 的 ${method} 方法`).toBeDefined()
  })
})

describe('响应方向：mock 样例按快照 schemas 校验（validateMock）', () => {
  it('pin-login/voice-login mock → ApiResponseLoginResponse 校验通过', () => {
    const schema = responseSchema('/api/v1/auth/pin-login', 'post')
    expect(schema).toBeDefined()
    const mock = { success: true, code: 0, message: 'ok', data: loginMock }
    expect(validateMock(mock, schema!, resolveRef)).toEqual([])
  })

  it('trial-register mock → ApiResponseTrialRegisterResponse 校验通过', () => {
    const schema = responseSchema('/api/v1/auth/trial/register', 'post')
    expect(schema).toBeDefined()
    const mock = { success: true, code: 0, message: 'ok', data: trialMock }
    expect(validateMock(mock, schema!, resolveRef)).toEqual([])
  })

  it('toolbox mock 数组 → ApiResponseListToolDefinition 校验通过（items 递归）', () => {
    const schema = responseSchema('/api/v1/toolbox', 'get')
    expect(schema).toBeDefined()
    const mock = { success: true, code: 0, message: 'ok', data: [toolMock] }
    expect(validateMock(mock, schema!, resolveRef)).toEqual([])
  })

  it('voiceprint/config mock → ApiResponseMapStringObject 容器通过 + data 字段断言', () => {
    const schema = responseSchema('/api/v1/voiceprint/config', 'get')
    expect(schema).toBeDefined()
    const mock = { success: true, code: 0, message: 'ok', data: voiceprintConfigMock }
    expect(validateMock(mock, schema!, resolveRef)).toEqual([])
    // Map 容器键由后端业务决定，测试内显式断言前端消费字段（mode/privacyNote）
    const data = mock.data as { mode?: unknown; privacyNote?: unknown }
    expect(typeof data.mode).toBe('string')
    expect(typeof data.privacyNote).toBe('string')
  })

  it('mood-check mock → ApiResponseMapStringObject 容器通过 + data 字段断言（对齐后端结构）', () => {
    const schema = responseSchema('/api/v1/toolbox/mood-check', 'post')
    expect(schema).toBeDefined()
    const mock = { success: true, code: 0, message: 'ok', data: moodCheckMock }
    expect(validateMock(mock, schema!, resolveRef)).toEqual([])
    const data = mock.data as Record<string, unknown>
    expect(data.needsAttention).toBe(false)
    expect(typeof data.level).toBe('string')
    expect(typeof data.delta).toBe('number')
    expect(data.toolId).toBe('grounding_54321')
  })

  it('sos/events 响应已文档化', () => {
    expect(responseSchema('/api/v1/sos/events', 'post')).toBeDefined()
  })

  it('负例：mock 字段类型与 schema 不符 → 校验报错', () => {
    const schema = responseSchema('/api/v1/toolbox', 'get')
    const badTool = { ...toolMock, durationSec: '60' }
    const mock = { success: true, code: 0, message: 'ok', data: [badTool] }
    const errors = validateMock(mock, schema!, resolveRef)
    expect(errors.some((e) => e.includes('durationSec'))).toBe(true)
  })

  it('负例：mock 含 schema 外字段（DTO 漂移）→ 校验报错', () => {
    const schema = responseSchema('/api/v1/toolbox', 'get')
    const drifted = { ...toolMock, effect: 'IMPROVED' }
    const mock = { success: true, code: 0, message: 'ok', data: [drifted] }
    const errors = validateMock(mock, schema!, resolveRef)
    expect(errors.some((e) => e.includes('effect'))).toBe(true)
  })

  it('负例：ToolDefinition.category 枚举外值 → 校验报错', () => {
    const schema = responseSchema('/api/v1/toolbox', 'get')
    const badCategory = { ...toolMock, category: 'UNKNOWN' }
    const mock = { success: true, code: 0, message: 'ok', data: [badCategory] }
    const errors = validateMock(mock, schema!, resolveRef)
    expect(errors.some((e) => e.includes('UNKNOWN'))).toBe(true)
  })
})
