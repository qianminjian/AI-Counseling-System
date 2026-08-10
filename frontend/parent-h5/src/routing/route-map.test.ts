// doing/73 T2（AC-9）：路由映射单一事实源测试
// 语义：Taro pages 路径 ↔ H5 URL 映射（config/index.ts h5.router.customRoutes 读取本模块），
// 保证迁移后四 URL（/parent/、/parent/privacy、/parent/report、/parent/consent）与迁移前
// React Router 路由完全等价；CFG-002（doing/84 §四.1）新增扫码入口页 /p/:v/:deviceCode
import { describe, it, expect } from 'vitest'
import { ROUTE_MAP, PAGES, toCustomRoutes } from './route-map'

describe('ROUTE_MAP（pages 路径 ↔ H5 URL）', () => {
  it('七页面映射齐备且 URL 唯一', () => {
    expect(ROUTE_MAP).toEqual({
      '/pages/verify/index': '/',
      '/pages/privacy/index': '/privacy',
      '/pages/report/index': '/report',
      '/pages/consent/index': '/consent',
      // CFG-002：扫码入口页（二维码 URL：/p/{v}/{deviceCode}）
      '/pages/device/index': '/p/:v/:deviceCode',
      // doing/85 TOC-001/002：toC 家庭版
      '/pages/toc-login/index': '/toc/login',
      '/pages/toc-profiles/index': '/toc/profiles',
    })
    const urls = Object.values(ROUTE_MAP)
    expect(new Set(urls).size).toBe(urls.length)
  })

  it('verify 为首页（URL /，与迁移前 Route path="/" 等价）', () => {
    expect(ROUTE_MAP['/pages/verify/index']).toBe('/')
  })

  it('与页面注册表 PAGES 一一对应（单一事实源一致，app.config.ts 引用 PAGES）', () => {
    const pagePaths = PAGES.map((p) => `/${p}`)
    expect(Object.keys(ROUTE_MAP).sort()).toEqual([...pagePaths].sort())
  })

  it('toCustomRoutes 输出可直接作为 Taro h5.router.customRoutes 配置', () => {
    expect(toCustomRoutes()).toEqual(ROUTE_MAP)
  })
})
