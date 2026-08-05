/**
 * TEST-006/M3：轻量 JSON Schema 校验器（零依赖，JSON Schema draft-07 子集）
 *
 * 两个入口：
 * - validateSchema：正向校验（required 强制）—— 校验器自身语义测试用
 * - validateMock：反向兼容校验（mock ⊆ schema，不强制 required）——
 *   前端契约测试用：mock 数据不允许出现 schema 外的字段（防漂移），
 *   允许按前端消费裁剪字段；null 值跳过校验（对应 Java 可空字段）
 *
 * 支持：type（含数组）/ required / properties / items / enum / $ref
 * 宽容：未知关键字忽略（additionalProperties / description / format 等）
 */
export interface Schema {
  type?: string | string[]
  required?: string[]
  properties?: Record<string, Schema>
  items?: Schema
  enum?: unknown[]
  $ref?: string
  [key: string]: unknown
}

export type RefResolver = (ref: string) => Schema | undefined

const PATH_ROOT = '$'

/** 沿 $ref 链解析（循环引用由调用方保证不存在——契约快照天然无环） */
function resolve(schema: Schema, resolveRef: RefResolver): Schema | null {
  if (typeof schema.$ref === 'string') {
    const target = resolveRef(schema.$ref)
    if (!target) return null
    return resolve(target, resolveRef)
  }
  return schema
}

function typeMatches(value: unknown, type: string): boolean {
  switch (type) {
    case 'string':
      return typeof value === 'string'
    case 'number':
      return typeof value === 'number'
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
    case 'boolean':
      return typeof value === 'boolean'
    case 'object':
      return value !== null && typeof value === 'object' && !Array.isArray(value)
    case 'array':
      return Array.isArray(value)
    case 'null':
      return value === null
    default:
      return true // 未知类型宽容（draft-07 之外的扩展类型）
  }
}

function typeName(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  return typeof value
}

/**
 * 核心递归校验。
 * @param strictRequired true=正向（required 缺失报错、未知字段忽略）；false=反向 mock（不强制 required、未知字段报错）
 */
function validateInner(
  value: unknown,
  schema: Schema,
  resolveRef: RefResolver,
  path: string,
  strictRequired: boolean,
): string[] {
  const resolved = resolve(schema, resolveRef)
  if (resolved === null) {
    return [`${path}: 无法解析 $ref ${schema.$ref}`]
  }

  const errors: string[] = []

  // ---- type 校验 ----
  if (resolved.type !== undefined) {
    const types = Array.isArray(resolved.type) ? resolved.type : [resolved.type]
    if (value === null) {
      // 反向 mock 模式：null 跳过（Java 可空字段序列化为 null 合法）
      if (strictRequired && !types.includes('null')) {
        errors.push(`${path}: 期望 ${types.join('/')}，实际为 null`)
      }
    } else if (!types.some((t) => typeMatches(value, t))) {
      errors.push(`${path}: 期望 ${types.join('/')}，实际为 ${typeName(value)}`)
    }
  }

  // ---- enum 校验 ----
  if (Array.isArray(resolved.enum) && !resolved.enum.includes(value)) {
    errors.push(`${path}: 值 ${JSON.stringify(value)} 不在枚举 ${JSON.stringify(resolved.enum)} 中`)
  }

  // ---- object 校验 ----
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const props = resolved.properties ?? {}
    if (strictRequired && Array.isArray(resolved.required)) {
      for (const key of resolved.required) {
        if (!(key in (value as Record<string, unknown>))) {
          errors.push(`${path}.${key}: 缺少必填字段`)
        }
      }
    }
    for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
      if (!(key in props)) {
        if (!strictRequired) {
          // 反向 mock：DTO（无 additionalProperties 声明）未知字段 = 漂移，报错；
          // Map 容器（显式 additionalProperties 对象，如 Map<String,Object>）
          // 值类型 OpenAPI 无法可靠表达，宽容允许（运行时键由业务决定）
          if (resolved.additionalProperties === false || resolved.additionalProperties === undefined) {
            errors.push(`${path}.${key}: 字段不在契约 schema 中`)
          }
        }
        continue
      }
      errors.push(...validateInner(val, props[key], resolveRef, `${path}.${key}`, strictRequired))
    }
  }

  // ---- array 校验 ----
  if (Array.isArray(value) && resolved.items) {
    value.forEach((item, i) => {
      errors.push(...validateInner(item, resolved.items!, resolveRef, `${path}[${i}]`, strictRequired))
    })
  }

  return errors
}

/** 正向校验：required 强制、未知字段忽略。返回错误信息数组（空数组 = 通过）。 */
export function validateSchema(value: unknown, schema: Schema, resolveRef: RefResolver): string[] {
  return validateInner(value, schema, resolveRef, PATH_ROOT, true)
}

/** 反向兼容校验：mock 字段 ⊆ schema（类型匹配、无 schema 外字段）、required 不强制、null 跳过。 */
export function validateMock(value: unknown, schema: Schema, resolveRef: RefResolver): string[] {
  return validateInner(value, schema, resolveRef, PATH_ROOT, false)
}
