/**
 * ARCH-008 F-7：teacher-web 契约防线（端点清单 ↔ api.ts 源码防漂移）
 *
 * 无 openapi 快照基建（student-h5 快照模式后续可升级），轻量起步：
 * - 清单自身质量：非空 / /api/v1 前缀 / 方法合法 / 无重复
 * - 防漂移：api.ts 源码中全部 API 字符串字面量（规范化后）⊆ 清单
 *   （新增/改动端点而不同步清单 → 测试红，契约防线的核心价值）
 *
 * 规范化规则（与 endpoints.ts 约定一致）：
 * - 模板占位符 ${x} 删除；query（? 后）剥离；连续斜杠归一；加 /api/v1 前缀
 */
import { describe, expect, it } from 'vitest'
import apiSource from '../api.ts?raw'
import { FRONTEND_ENDPOINTS } from '../api/endpoints'

const VALID_METHODS = ['get', 'post', 'put', 'patch', 'delete']

/** 源码字符串字面量 → 规范化 API 路径（非 API 路径返回 null） */
function normalizeCandidate(raw: string): string | null {
  let s = raw
    .replace(/\$\{[^}]*\}/g, '') // 模板占位符删除
    .replace(/\$\{[^}]*$/g, '') // 未闭合占位符残段（模板内嵌套引号切割所致）
  s = s.split('?')[0] // query 剥离
  s = s.trim() // 模板切割残留空白
  if (!s.startsWith('/')) return null
  s = s.replace(/\/+/g, '/').replace(/\/+$/, '') // 斜杠归一 + 尾部斜杠去除
  if (!s.startsWith('/api/v1')) {
    if (s === '/api/v1') return null // BASE_URL 常量本身
    s = `/api/v1${s}` // api() 拼接形态（path 无前缀）
  }
  if (s === '/api/v1') return null
  return s
}

/** 源码中全部 API 路径（去重，规范化形态） */
function sourceApiPaths(): string[] {
  const strings = [...apiSource.matchAll(/['"`]([^'"`\n]*)['"`]/g)].map((m) => m[1])
  const paths = new Set<string>()
  for (const raw of strings) {
    const norm = normalizeCandidate(raw)
    if (norm) paths.add(norm)
  }
  return [...paths]
}

const SOURCE_PATHS = sourceApiPaths()
const LIST_PATHS = FRONTEND_ENDPOINTS.map(([p]) => p)

describe('端点清单自身质量（FRONTEND_ENDPOINTS）', () => {
  it('清单非空', () => {
    expect(FRONTEND_ENDPOINTS.length).toBeGreaterThan(10)
  })

  it.each(FRONTEND_ENDPOINTS)('%s %s 格式合法', (path, method) => {
    expect(path).toMatch(/^\/api\/v1\//)
    expect(VALID_METHODS).toContain(method)
  })

  it('无重复条目（path+method）', () => {
    const keys = FRONTEND_ENDPOINTS.map(([p, m]) => `${p} ${m}`)
    expect(new Set(keys).size).toBe(keys.length)
  })
})

describe('防漂移：api.ts 源码路径 ⊆ 清单', () => {
  it('源码中提取到 API 路径（提取逻辑有效）', () => {
    expect(SOURCE_PATHS.length).toBeGreaterThan(10)
  })

  it('清单覆盖源码全部端点（新增端点必须登记）', () => {
    const missing = SOURCE_PATHS.filter((p) => !LIST_PATHS.includes(p))
    expect(missing).toEqual([])
  })
})
