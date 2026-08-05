/**
 * TEST-006/M3：轻量 JSON Schema 校验器单测（TDD）
 * 覆盖：type/required/nested/items/enum/$ref/未知关键字宽容/validateMock 反向兼容
 */
import { describe, expect, it } from 'vitest'
import { validateMock, validateSchema, type Schema } from '../__contract__/schemaValidator'

const schemas: Record<string, Schema> = {
  ApiResponseVoid: { type: 'object', properties: { success: { type: 'boolean' } } },
  LoginResponse: {
    type: 'object',
    required: ['token', 'userId'],
    properties: {
      token: { type: 'string' },
      refreshToken: { type: 'string' },
      userId: { type: 'string' },
      displayName: { type: 'string' },
    },
  },
  ToolDefinition: {
    type: 'object',
    required: ['toolId'],
    properties: {
      toolId: { type: 'string' },
      title: { type: 'string' },
      durationSec: { type: 'integer' },
      rewardBadge: { type: 'string' },
      category: { enum: ['BREATHING', 'GROUNDING'] },
    },
  },
  ToolList: { type: 'array', items: { $ref: '#/components/schemas/ToolDefinition' } },
  EnrollRequest: {
    type: 'object',
    required: ['embeddings'],
    properties: { embeddings: { type: 'array', items: { type: 'array', items: { type: 'number' } } } },
  },
}

const resolveRef = (ref: string): Schema | undefined => {
  const name = ref.replace('#/components/schemas/', '')
  return schemas[name]
}

describe('validateSchema（正向：required 强制）', () => {
  it('string 类型通过', () => {
    expect(validateSchema('abc', { type: 'string' }, resolveRef)).toEqual([])
  })

  it('number 类型通过', () => {
    expect(validateSchema(3.14, { type: 'number' }, resolveRef)).toEqual([])
  })

  it('类型不符报错', () => {
    const errors = validateSchema(123, { type: 'string' }, resolveRef)
    expect(errors.length).toBe(1)
    expect(errors[0]).toContain('期望 string')
  })

  it('integer 拒绝小数', () => {
    expect(validateSchema(1.5, { type: 'integer' }, resolveRef).length).toBe(1)
  })

  it('required 缺失报错', () => {
    const errors = validateSchema({ userId: '1' }, schemas.LoginResponse, resolveRef)
    expect(errors.some((e) => e.includes('token'))).toBe(true)
  })

  it('required 满足通过', () => {
    expect(validateSchema({ token: 't', userId: '1' }, schemas.LoginResponse, resolveRef)).toEqual([])
  })

  it('嵌套 object 校验', () => {
    const schema: Schema = { type: 'object', properties: { meta: { type: 'object', properties: { v: { type: 'number' } } } } }
    expect(validateSchema({ meta: { v: 'x' } }, schema, resolveRef).length).toBe(1)
    expect(validateSchema({ meta: { v: 1 } }, schema, resolveRef)).toEqual([])
  })

  it('array + items 校验', () => {
    expect(validateSchema([1, 'x'], { type: 'array', items: { type: 'number' } }, resolveRef).length).toBe(1)
    expect(validateSchema([1, 2], { type: 'array', items: { type: 'number' } }, resolveRef)).toEqual([])
  })

  it('enum 匹配通过 / 不匹配报错', () => {
    expect(validateSchema('GROUNDING', schemas.ToolDefinition.properties.category, resolveRef)).toEqual([])
    expect(validateSchema('OTHER', schemas.ToolDefinition.properties.category, resolveRef).length).toBe(1)
  })

  it('$ref 解析为对应 schema 校验', () => {
    const errors = validateSchema([{ toolId: 123 }], schemas.ToolList, resolveRef)
    expect(errors.length).toBe(1)
    expect(validateSchema([{ toolId: 't1' }], schemas.ToolList, resolveRef)).toEqual([])
  })

  it('$ref 无法解析时明确报错', () => {
    const errors = validateSchema({}, { $ref: '#/components/schemas/Missing' }, resolveRef)
    expect(errors[0]).toContain('无法解析')
  })

  it('null 类型通过 / 非 null 报错', () => {
    expect(validateSchema(null, { type: 'null' }, resolveRef)).toEqual([])
    expect(validateSchema(1, { type: 'null' }, resolveRef).length).toBe(1)
  })

  it('type 数组（多类型允许）', () => {
    expect(validateSchema('s', { type: ['string', 'null'] }, resolveRef)).toEqual([])
    expect(validateSchema(null, { type: ['string', 'null'] }, resolveRef)).toEqual([])
  })

  it('未知关键字宽容（additionalProperties/description 不报错）', () => {
    const schema: Schema = { type: 'object', additionalProperties: false, description: 'x' }
    expect(validateSchema({ any: 1 }, schema, resolveRef)).toEqual([])
  })
})

describe('validateMock（反向：mock ⊆ schema，不强制 required）', () => {
  it('裁剪字段的 mock 通过（required 字段缺失合法）', () => {
    expect(validateMock({ token: 't' }, schemas.LoginResponse, resolveRef)).toEqual([])
  })

  it('mock 含 schema 外字段报错（漂移暴露）', () => {
    const errors = validateMock({ token: 't', effect: 'IMPROVED' }, schemas.LoginResponse, resolveRef)
    expect(errors.some((e) => e.includes('effect'))).toBe(true)
  })

  it('mock 类型与 schema 不符报错', () => {
    expect(validateMock({ userId: 123 }, schemas.LoginResponse, resolveRef).length).toBe(1)
  })

  it('嵌套 object 递归校验', () => {
    const schema: Schema = { type: 'object', properties: { data: { type: 'object', properties: { mode: { type: 'string' } } } } }
    expect(validateMock({ data: { mode: 1 } }, schema, resolveRef).length).toBe(1)
  })

  it('数组 items 递归校验（$ref）', () => {
    expect(validateMock([{ toolId: 't1' }], schemas.ToolList, resolveRef)).toEqual([])
    expect(validateMock([{ toolId: 't1', bogus: 1 }], schemas.ToolList, resolveRef).length).toBe(1)
  })

  it('null 值跳过校验（Java 可空字段兼容）', () => {
    expect(validateMock({ rewardBadge: null }, schemas.ToolDefinition, resolveRef)).toEqual([])
  })

  it('enum 不匹配报错', () => {
    expect(validateMock({ category: 'UNKNOWN' }, schemas.ToolDefinition, resolveRef).length).toBe(1)
  })

  it('数组内嵌数组 items 校验', () => {
    const schema: Schema = { type: 'object', properties: { embeddings: { type: 'array', items: { type: 'array', items: { type: 'number' } } } } }
    expect(validateMock({ embeddings: [[0.1, 0.2]] }, schema, resolveRef)).toEqual([])
    expect(validateMock({ embeddings: [['x']] }, schema, resolveRef).length).toBe(1)
  })
})
