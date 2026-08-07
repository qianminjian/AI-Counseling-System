import path from 'node:path'
import { defineConfig, type UserConfigExport } from '@tarojs/cli'
import { toCustomRoutes } from '../src/routing/route-map'

// doing/73 D2 决策（R8）：compiler.type = 'webpack5'（Taro 4 默认，稳定优先）
// 平台耦合点：h5.router（browser + basename /parent）与 publicPath 对齐迁移前 URL/部署
export default defineConfig<'webpack5'>(async (merge) => {
  const baseConfig: UserConfigExport<'webpack5'> = {
    projectName: 'parent-h5',
    date: '2026-08-07',
    designWidth: 750,
    deviceRatio: {
      640: 2.34 / 2,
      750: 1,
      375: 2,
      828: 1.81 / 2,
    },
    sourceRoot: 'src',
    outputRoot: 'dist',
    plugins: ['@tarojs/plugin-framework-react', '@tarojs/plugin-platform-h5'],
    defineConstants: {},
    copy: {
      patterns: [],
      options: {},
    },
    framework: 'react',
    compiler: {
      type: 'webpack5',
      prebundle: { enable: false },
    },
    cache: {
      enable: false,
    },
    // DC-005：共享认证传输模块 alias（tsconfig paths 同步）
    alias: {
      '@shared': path.resolve(__dirname, '../../shared/src'),
    },
    mini: {
      postcss: {
        pxtransform: { enable: true },
        cssModules: { enable: false },
      },
    },
    h5: {
      publicPath: '/parent/',
      staticDirectory: 'static',
      // doing/73 C1：URL 与部署完全兼容（/parent/、/parent/privacy、/parent/report、/parent/consent）
      router: {
        mode: 'browser',
        basename: '/parent',
        // doing/73 T2：pages 路径 → H5 URL 映射（与迁移前 React Router 四路由等价，route-map.ts 为单一事实源）
        customRoutes: toCustomRoutes(),
      },
      // 视觉零变化（doing/73 §3.6）：关闭 pxtransform，保留现状 px 值
      postcss: {
        autoprefixer: { enable: true },
        pxtransform: { enable: false },
        cssModules: { enable: false },
      },
      // 对齐迁移前 dev 体验：端口 5174 + /api 代理 8080
      devServer: {
        port: 5174,
        proxy: {
          '/api': {
            target: 'http://localhost:8080',
            changeOrigin: true,
          },
        },
      },
    },
  }
  return merge({}, baseConfig)
})
