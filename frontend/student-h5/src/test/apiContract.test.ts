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

/**
 * F-01（doing/98）：消费面双向校验——src 生产代码中全部 API 路径字面量 ⊆ 常量表。
 * 原单向断言（常量表 ⊆ 快照）无法拦截组件内联硬编码路径（diary/relaxation/achievements 曾长期表外），
 * 本扫描保证新增端点必须登记常量表（单一事实源闭环），防回潮。
 */
describe('消费面：src 生产代码 API 路径字面量 ⊆ 常量表（F-01）', () => {
  // 相对路径锚定（vite root 的 '/src' 绝对锚点在本仓库解析不稳定，仅命中 2 文件）
  const sources: Record<string, string> = import.meta.glob('../**/*.{ts,tsx}', {
    query: '?raw',
    import: 'default',
    eager: true,
  })

  /** 路径字面量 → 规范化契约形态（占位符剥离与 FRONTEND_ENDPOINTS 派生规则一致） */
  function toContractPath(raw: string): string | null {
    let s = raw
      .replace(/\$\{[^}]*\}/g, '') // 模板占位符删除
      .replace(/\$\{[^}]*$/g, '') // 未闭合占位符残段
    s = s.split('?')[0] // query 剥离
    s = s.trim()
    if (!s.startsWith('/')) return null
    if (!s.startsWith('/api/v1')) {
      if (s === '/api/v1') return null
      s = `/api/v1${s}`
    }
    s = s.replace(/\{(\w+)\}/g, '') // {id}/{sessionId} 占位符剥离（留双斜杠，与清单派生一致）
    s = s.replace(/\/+/g, '/').replace(/\/+$/, '')
    return s
  }

  const listPaths = FRONTEND_ENDPOINTS.map(([p]) => p)
  const unmatched: Array<{ file: string; path: string }> = []
  let scanned = 0
  let fillPathCalls = 0

  for (const [file, src] of Object.entries(sources)) {
    // 相对 glob 的 key 形态：'./useSseStream.test.ts' / '../components/xxx.tsx'——按测试文件特征排除
    if (file.includes('.test.') || file.includes('/test/') || file.includes('/__contract__/')) continue
    const pattern = /(?:api|publicFetch|authFetch|streamMessage)\(\s*(['"`])([^'"`]+)\1/g
    for (const m of src.matchAll(pattern)) {
      scanned += 1
      const norm = toContractPath(m[2])
      if (norm && !listPaths.includes(norm)) {
        unmatched.push({ file, path: norm })
      }
    }
    // 常量表消费面计数（F-01 后生产代码路径字面量已清零，fillPath 是主要消费形态）
    fillPathCalls += (src.match(/fillPath\(ENDPOINTS\.\w+\.path/g) ?? []).length
  }

  it('生产代码存在可扫描的 API 调用（扫描逻辑有效）', () => {
    // 常量表消费为混合形态（fillPath + 直接 ENDPOINTS.xxx.path 引用），阈值只需证明扫描器在真实工作
    expect(scanned + fillPathCalls).toBeGreaterThan(10)
  })

  it('全部路径字面量已登记常量表（新增端点必须登记，禁止表外硬编码）', () => {
    expect(unmatched).toEqual([])
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
