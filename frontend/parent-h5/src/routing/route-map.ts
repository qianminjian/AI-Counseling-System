/**
 * doing/73 T2（AC-9）：路由映射单一事实源
 * Taro pages 路径 ↔ H5 URL（basename=/parent 前缀后的路径，与迁移前 React Router 四路由完全等价）：
 * - /parent/           → verify（登录/注册页，pages[0] 首页约定）
 * - /parent/privacy    → privacy
 * - /parent/report     → report（需登录）
 * - /parent/consent    → consent（需登录）
 * config/index.ts 的 h5.router.customRoutes 读取本模块，避免映射散落两处
 */

/** 页面注册表单一事实源（app.config.ts pages 读取本数组，首页约定 pages[0] = verify） */
export const PAGES = [
  'pages/verify/index',
  'pages/privacy/index',
  'pages/report/index',
  'pages/consent/index',
  'pages/device/index',
] as const

export const ROUTE_MAP = {
  '/pages/verify/index': '/',
  '/pages/privacy/index': '/privacy',
  '/pages/report/index': '/report',
  '/pages/consent/index': '/consent',
  // CFG-002（doing/84 §四.1）：扫码入口页（二维码 URL：/p/{v}/{deviceCode}，Taro 路径参数）
  '/pages/device/index': '/p/:v/:deviceCode',
} as const

export function toCustomRoutes(): Record<string, string> {
  return { ...ROUTE_MAP }
}
