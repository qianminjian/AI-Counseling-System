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
import { FRONTEND_ENDPOINTS } from '../endpoints' // doing/94 R-001：端点清单由常量表派生（原本地硬编码 23 条已迁出）
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

/**
 * 快照 key 匹配（doing/94 R-001）：派生清单为占位符剥离形态（模板端点留双斜杠），
 * 快照 key 为 OpenAPI 占位符形态——双斜杠位回填参数占位正则进行匹配。
 * 已知边界（CodeReview L1，当前 23 端点不受影响）：占位符位于路径末段或多占位符
 * 形态的模板端点无法回填，新增此类端点时需扩展本函数。
 */
function snapshotPath(candidate: string): string | undefined {
  if (doc.paths[candidate]) return candidate
  // 函数替换返回字面值（替换串会解析反斜杠转义）；双斜杠位回填"参数占位 + 尾部斜杠"正则段
  const re = new RegExp(
    '^' + candidate.replace(/\/\//g, () => '/\\{[^/]+\\}/').replace(/\//g, () => '\\/') + '$'
  )
  return Object.keys(doc.paths).find((k) => re.test(k))
}

// ===== 前端端点清单（doing/94 R-001：由 endpoints.ts 常量表派生，占位符剥离） =====
// ===== 响应 mock 样例（单一来源：src/test/mockFixtures.ts，ARCH-005 F-3） =====

describe('契约快照结构', () => {
  it('快照为合法 OpenAPI 精简文档（info/paths/components.schemas）', () => {
    expect(doc.info.title).toContain('MindSafe')
    expect(Object.keys(doc.paths).length).toBeGreaterThan(0)
    expect(Object.keys(doc.components.schemas).length).toBeGreaterThan(0)
  })

  it('学生端 23 个端点全部在快照中', () => {
    for (const [path] of FRONTEND_ENDPOINTS) {
      expect(snapshotPath(path), `快照缺少端点 ${path}`).toBeDefined()
    }
  })
})

describe('请求方向：前端端点 ⊆ 契约快照（方法匹配）', () => {
  it.each(FRONTEND_ENDPOINTS)('%s %s', (path, method) => {
    const matched = snapshotPath(path)
    const op = matched ? doc.paths[matched]?.[method] : undefined
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
