/**
 * ARCH-008 F-7：parent-h5 契约防线（端点清单 ↔ services/index.ts 源码防漂移）
 *
 * 与 teacher-web 同款轻量模式（无 openapi 快照基建）：
 * - 清单自身质量：非空 / /api/v1 前缀 / 方法合法 / 无重复
 * - 防漂移：services/index.ts 源码中全部 API 字符串字面量（规范化后）⊆ 清单
 *
 * 规范化规则：
 * - 模板占位符 ${x} 删除（含未闭合残段）；query（? 后）剥离；trim；
 *   斜杠归一 + 尾部斜杠去除；非 /api/v1 前缀路径拼接 /api/v1（request() 拼接形态）
 */
import { describe, expect, it } from 'vitest'
import apiSource from '../services/index.ts?raw'
import { FRONTEND_ENDPOINTS } from '../services/endpoints'

const VALID_METHODS = ['get', 'post', 'put', 'patch', 'delete']

/** 源码字符串字面量 → 规范化 API 路径（非 API 路径返回 null） */
function normalizeCandidate(raw: string): string | null {
  let s = raw
    .replace(/\$\{[^}]*\}/g, '') // 模板占位符删除
    .replace(/\$\{[^}]*$/g, '') // 未闭合占位符残段（模板内嵌套引号切割所致）
  s = s.split('?')[0] // query 剥离
  s = s.trim()
  if (!s.startsWith('/')) return null
  s = s.replace(/\/+/g, '/').replace(/\/+$/, '')
  if (!s.startsWith('/api/v1')) {
    if (s === '/api/v1') return null // BASE_URL 常量本身
    s = `/api/v1${s}` // request() 拼接形态（path 无前缀）
  }
  if (s === '/api/v1') return null
  return s
}

/** 源码中全部 API 路径（去重，规范化形态；仅提取 request/fetch 调用内字面量，排除路由跳转如 location.href） */
function sourceApiPaths(): string[] {
  const patterns = [
    // doing/94 R-003：工厂单例化后调用名 parentRequest/tocRequest（原 request 收敛）
    /(?:parentRequest|tocRequest|request)(?:<[^>]+>)?\(\s*(['"`])([^'"`]+)\1/g, // parentRequest('/path')
    /fetch\(\s*(['"`])([^'"`]*)\1/g, // fetch('/path') / fetch(`...`)
  ]
  const paths = new Set<string>()
  for (const re of patterns) {
    for (const m of apiSource.matchAll(re)) {
      const norm = normalizeCandidate(m[2])
      if (norm) paths.add(norm)
    }
  }
  return [...paths]
}

const SOURCE_PATHS = sourceApiPaths()
const LIST_PATHS = FRONTEND_ENDPOINTS.map(([p]) => p)

describe('端点清单自身质量（FRONTEND_ENDPOINTS）', () => {
  it('清单非空', () => {
    expect(FRONTEND_ENDPOINTS.length).toBeGreaterThanOrEqual(5)
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

describe('防漂移：services/index.ts 源码路径 ⊆ 清单', () => {
  it('源码中提取到 API 路径（提取逻辑有效）', () => {
    // DC-005 收敛后 request 端点 4 个（refresh 移入共享模块，不再出现在本文件）
    expect(SOURCE_PATHS.length).toBeGreaterThanOrEqual(4)
  })

  it('清单覆盖源码全部端点（新增端点必须登记）', () => {
    const missing = SOURCE_PATHS.filter((p) => !LIST_PATHS.includes(p))
    expect(missing).toEqual([])
  })
})
