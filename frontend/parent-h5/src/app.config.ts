// doing/73 C1 路由：pages 注册（P0 H5 只启用 H5 端；P1 小程序端同注册表直接可用）
// 首页约定：pages[0] 为默认页（verify 登录/注册，URL /parent/）
// PAGES 单一事实源在 src/routing/route-map.ts（T2：与 customRoutes 映射保持一致）
/// <reference types="@tarojs/taro" />
import { PAGES } from './routing/route-map'

export default defineAppConfig({
  pages: [...PAGES],
  window: {
    navigationStyle: 'custom',
    backgroundColor: '#f5f6fa',
  },
})
