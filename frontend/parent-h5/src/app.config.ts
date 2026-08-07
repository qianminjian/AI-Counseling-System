// doing/73 C1 路由：pages 注册（P0 H5 只启用 H5 端；P1 小程序端同注册表直接可用）
// 首页约定：pages[0] 为默认页（verify 登录/注册，URL /parent/）
/// <reference types="@tarojs/taro" />

export default defineAppConfig({
  pages: [
    'pages/verify/index',
    'pages/privacy/index',
    'pages/report/index',
    'pages/consent/index',
  ],
  window: {
    navigationStyle: 'custom',
    backgroundColor: '#f5f6fa',
  },
})
