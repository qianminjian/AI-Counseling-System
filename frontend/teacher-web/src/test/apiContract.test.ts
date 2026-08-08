/**
 * ARCH-008 F-7 + FA-15：teacher-web 契约防线（端点常量表单一事实源）
 *
 * FA-15 升级：端点路径此前三处镜像（api.ts 硬编码 + 清单 + 正则扫源码断言），
 * 现在路径只存在于 ENDPOINTS 常量表，api()/callEndpoint()/downloadBlob 全部消费。
 * 测试相应从「正则扫字符串 ⊆ 清单」升级为「直接校验常量表」：
 * - 常量表自身质量：/api/v1 前缀 / 方法合法 / 无重复
 * - 清单派生正确性：FRONTEND_ENDPOINTS 与常量表逐条对应（占位符剥离）
 * - 消费面 ↔ 常量表双向校验：引用 key 全有效（防拼错）+ 除共享层端点外无死条目
 */
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ENDPOINTS, FRONTEND_ENDPOINTS } from '../api/endpoints'

const VALID_METHODS = ['get', 'post', 'put', 'patch', 'delete']

const KEYS = Object.keys(ENDPOINTS) as Array<keyof typeof ENDPOINTS>

/** src 下全部生产源码（排除 src/test，消费面扫描；测试自身引用常量表属性属正常） */
function srcFiles(): string[] {
  const root = join(process.cwd(), 'src')
  const out: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (entry.name === 'test') continue // 消费面扫描不包含测试目录
        walk(p)
      }
      else if (/\.[jt]sx?$/.test(entry.name)) out.push(readFileSync(p, 'utf-8'))
    }
  }
  walk(root)
  return out
}

describe('端点常量表自身质量（ENDPOINTS，FA-15）', () => {
  it('常量表非空', () => {
    expect(KEYS.length).toBeGreaterThan(10)
  })

  it('path 全为 /api/v1 前缀模板，method 合法', () => {
    for (const key of KEYS) {
      expect(ENDPOINTS[key].path, key).toMatch(/^\/api\/v1\//)
      expect(VALID_METHODS, key).toContain(ENDPOINTS[key].method)
    }
  })

  it('静态端点（无占位符）间无重复 path+method；模板端点剥离形态可碰撞（如 getStudentProfile{id} ↔ getStudents，消费面精确无混淆）', () => {
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

describe('契约清单派生（FRONTEND_ENDPOINTS ← ENDPOINTS，FA-15）', () => {
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

  it('清单格式合法（重复检查已在常量表侧覆盖，模板端点剥离形态允许碰撞）', () => {
    expect(FRONTEND_ENDPOINTS.length).toBeGreaterThan(10)
    for (const [path, method] of FRONTEND_ENDPOINTS) {
      expect(path).toMatch(/^\/api\/v1\//)
      expect(VALID_METHODS).toContain(method)
    }
  })
})

describe('消费面 ↔ 常量表双向校验（FA-15 直接校验，替代正则扫字符串）', () => {
  const sources = srcFiles().join('\n')
  // callEndpoint('key' / downloadBlob('key'（字符串引用，method 参数有无均可）| ENDPOINTS.静态Key
  const REF_RE = /(?:callEndpoint|downloadBlob)\('(\w+)'|ENDPOINTS\.(\w+)/g
  const refKeys = new Set<string>()
  for (const m of sources.matchAll(REF_RE)) {
    if (m[1]) refKeys.add(m[1])
    if (m[2]) refKeys.add(m[2])
  }

  it('引用到的常量表 key 全部有效（拼错/未登记即红）', () => {
    const invalid = [...refKeys].filter((k) => !(k in ENDPOINTS))
    expect(invalid).toEqual([])
  })

  it('常量表无死条目：除共享层端点（authRefresh）外每个 key 都有消费点', () => {
    const sharedOnly = new Set(['authRefresh']) // 消费在 shared/auth-transport，不在 src 源码面
    const dead = KEYS.filter((k) => !sharedOnly.has(k) && !refKeys.has(k))
    expect(dead).toEqual([])
  })
})
