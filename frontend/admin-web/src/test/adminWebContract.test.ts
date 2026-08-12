/**
 * 板块08 P1-1：admin-web 契约防线（端点常量表单一事实源，对齐 teacher-web FA-15 模式）
 *
 * 此前 32+ 处 /api/v1 路径硬编码散落 api.ts（含 8 处裸 fetch），R-001 三端治理唯独漏接本端。
 * 现在路径只存在于 api/endpoints.ts 常量表，adminFetch / postAdmin / platformLogin 全部消费。
 * 测试直接校验常量表（teacher-web 同款升级形态）：
 * - 常量表自身质量：/api/v1 前缀 / 方法合法 / 无重复
 * - 清单派生正确性：FRONTEND_ENDPOINTS 与常量表逐条对应（占位符剥离）
 * - 消费面 ↔ 常量表双向校验：引用 key 全有效（防拼错）+ 无死条目
 * - 强约束：生产源码（除 endpoints.ts 自身）不得出现 /api/v1 字面量（34 处硬编码清零防回退）
 */
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ENDPOINTS, FRONTEND_ENDPOINTS } from '../api/endpoints'

const VALID_METHODS = ['get', 'post', 'put', 'patch', 'delete']

const KEYS = Object.keys(ENDPOINTS) as Array<keyof typeof ENDPOINTS>

/** src 下全部生产源码（排除 src/test，消费面扫描；测试自身引用常量表属性属正常） */
function srcFiles(): Array<{ path: string; content: string }> {
  const root = join(process.cwd(), 'src')
  const out: Array<{ path: string; content: string }> = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'test') continue // 消费面扫描不包含测试目录
        walk(p)
      }
      else if (/\.[jt]sx?$/.test(entry.name)) out.push({ path: p, content: readFileSync(p, 'utf-8') })
    }
  }
  walk(root)
  return out
}

describe('端点常量表自身质量（ENDPOINTS，P1-1）', () => {
  it('常量表非空', () => {
    expect(KEYS.length).toBeGreaterThan(10)
  })

  it('path 全为 /api/v1 前缀模板，method 合法', () => {
    for (const key of KEYS) {
      expect(ENDPOINTS[key].path, key).toMatch(/^\/api\/v1\//)
      expect(VALID_METHODS, key).toContain(ENDPOINTS[key].method)
    }
  })

  it('静态端点（无占位符）间无重复 path+method；模板端点剥离形态可碰撞（如 configRegistry ↔ updateConfig，消费面精确无混淆）', () => {
    const seen = new Set<string>()
    const dupes: string[] = []
    for (const key of KEYS) {
      const ep = ENDPOINTS[key]
      if (/\{\w+\}/.test(ep.path)) continue // 模板端点：占位符剥离后允许与静态端点同形
      const k = `${ep.path} ${ep.method}`
      if (seen.has(k)) dupes.push(k)
      seen.add(k)
    }
    expect(dupes).toEqual([])
  })
})

describe('契约清单派生（FRONTEND_ENDPOINTS ← ENDPOINTS，P1-1）', () => {
  it('条目数一致（逐条映射，无手写增删）', () => {
    expect(FRONTEND_ENDPOINTS.length).toBe(KEYS.length)
  })

  it('占位符剥离正确：{name} → 空，且无 query/尾部斜杠', () => {
    for (const key of KEYS) {
      const ep = ENDPOINTS[key]
      const expected = ep.path.replace(/\{\w+\}/g, '').replace(/\/+$/, '')
      const actual = FRONTEND_ENDPOINTS.find(([p, m]) => m === ep.method && p === expected)
      expect(actual, key).toBeDefined()
      expect(actual![1]).toBe(ep.method)
    }
  })

  it('清单格式合法', () => {
    expect(FRONTEND_ENDPOINTS.length).toBeGreaterThan(10)
    for (const [path, method] of FRONTEND_ENDPOINTS) {
      expect(path).toMatch(/^\/api\/v1\//)
      expect(VALID_METHODS).toContain(method)
    }
  })
})

describe('消费面 ↔ 常量表双向校验（P1-1 直接校验，替代正则扫字符串）', () => {
  const files = srcFiles()
  const sources = files.map((f) => f.content).join('\n')
  // 消费形态：ENDPOINTS.<key>.path（adminFetch/postAdmin/fillPath/platformLogin 全部按 key 消费）
  const REF_RE = /ENDPOINTS\.(\w+)/g
  const refKeys = new Set<string>()
  for (const m of sources.matchAll(REF_RE)) {
    if (m[1]) refKeys.add(m[1])
  }

  it('引用到的常量表 key 全部有效（拼错/未登记即红）', () => {
    const invalid = [...refKeys].filter((k) => !(k in ENDPOINTS))
    expect(invalid).toEqual([])
  })

  it('常量表无死条目：每个 key 都有消费点', () => {
    const dead = KEYS.filter((k) => !refKeys.has(k))
    expect(dead).toEqual([])
  })

  it('强约束：生产源码无 /api/v1 字面量（endpoints.ts 自身除外），34 处硬编码清零', () => {
    const offenders = files
      .filter((f) => !f.path.endsWith(`${join('api', 'endpoints.ts')}`))
      .filter((f) => f.content.includes('/api/v1'))
      .map((f) => f.path)
    expect(offenders).toEqual([])
  })
})
