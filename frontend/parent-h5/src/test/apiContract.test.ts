/**
 * ARCH-008 F-7：parent-h5 契约防线（端点常量表 ↔ services/*.ts 消费面双向校验）
 *
 * F-16（doing/98）：常量表由「仅供测试校验的快照」升级为「生产代码事实源」——
 * 三个服务文件（index/device/toc）统一经 apiPath(key) 消费常量表，本测试负责：
 * - 清单自身质量：非空 / /api/v1 前缀 / 方法合法 / 无重复
 * - 消费面 key 合法：services/*.ts 中 apiPath('KEY') 全部为合法 EndpointKey
 * - 路径覆盖：apiPath(key) 解析出的规范路径 ⊆ 清单（常量表路径变更即红）
 * - 防回潮：services/*.ts 禁止再出现 request/fetch 直接跟字符串字面量路径（硬编码回归）
 */
import { describe, expect, it } from 'vitest'
import apiIndexSource from '../services/index.ts?raw'
import apiDeviceSource from '../services/device.ts?raw'
import apiTocSource from '../services/toc.ts?raw'
import { ENDPOINTS, fillPath, FRONTEND_ENDPOINTS } from '../services/endpoints'

const VALID_METHODS = ['get', 'post', 'put', 'patch', 'delete']

const SERVICE_SOURCES = [apiIndexSource, apiDeviceSource, apiTocSource]

/** 规范化 API 路径（与常量表派生规则一致：query 剥离 / 斜杠归一 / 尾部斜杠去除） */
function normalize(path: string): string {
  let s = path.split('?')[0]
  s = s.replace(/\/+/g, '/').replace(/\/+$/, '')
  return s
}

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

describe('消费面：services/*.ts 的 apiPath(key) 全部合法且被清单覆盖', () => {
  const usedKeys = new Set<string>()
  for (const src of SERVICE_SOURCES) {
    for (const m of src.matchAll(/apiPath\(\s*'([^']+)'/g)) {
      usedKeys.add(m[1])
    }
  }

  it('服务层消费了常量表（apiPath 调用存在）', () => {
    expect(usedKeys.size).toBeGreaterThanOrEqual(10)
  })

  it('消费的 key 全部为合法 EndpointKey（拼写错误即红）', () => {
    const illegal = [...usedKeys].filter((k) => !(k in ENDPOINTS))
    expect(illegal).toEqual([])
  })

  it('apiPath(key) 解析路径 ⊆ 清单（常量表路径变更或新增端点未登记即红）', () => {
    // 双方均归一化：fillPath 空串替换占位符与清单派生的双斜杠形态，统一到斜杠归一形态比对
    const listPaths = FRONTEND_ENDPOINTS.map(([p]) => normalize(p))
    const resolved = [...usedKeys].map((k) => normalize(fillPath(ENDPOINTS[k as keyof typeof ENDPOINTS].path, {})))
    const missing = resolved.filter((p) => !listPaths.includes(p))
    expect(missing).toEqual([])
  })
})

describe('防回潮：services/*.ts 禁止硬编码路径字面量（必须走 apiPath）', () => {
  it('无 request/fetch 直接跟字符串路径字面量的调用', () => {
    const hardcoded: string[] = []
    for (const src of SERVICE_SOURCES) {
      for (const m of src.matchAll(/(?:parentRequest|tocRequest|request)(?:<[^>]+>)?\(\s*(['"`])([^'"`]+)\1/g)) {
        hardcoded.push(m[2])
      }
    }
    expect(hardcoded).toEqual([])
  })
})
