import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'node:path'

export default defineConfig({
  // DC-005：共享模块位于 root 之外（../shared），Vite fs.allow 放行否则无法加载
  server: {
    fs: {
      allow: ['.', '../shared/src'],
    },
  },
  plugins: [react()],
  // doing/73 T0 spike（R7）：@tarojs/components 主入口为 Stencil web components（ES class），
  // react-dom 无法直接调用；Taro 4.2 提供 lib/react 入口（reactifyWebComponent 包装 + 原生事件绑定），
  // 精确 alias 指向该入口（正则避免误伤 components.js 内部 '@tarojs/components/dist/*' 导入）。
  // 生产构建走 Taro CLI（config/index.ts），不读本文件，不受影响。
  resolve: {
    alias: [
      {
        find: /^@tarojs\/components$/,
        replacement: path.resolve(
          __dirname,
          'node_modules/@tarojs/components/lib/react/index.js'
        ),
      },
    ],
  },
  test: {
    // 内存保护：vitest 强制串行（等价 CLI --maxWorkers=1，配置级生效不可绕过）
    poolOptions: {
      threads: {
        maxThreads: 1,
        minThreads: 1,
      },
    },
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // DC-005：共享认证传输模块纳入三端门禁（相对导入共享源码方案）
    include: ['src/**/*.{test,spec}.{ts,tsx}', '../shared/src/**/*.test.ts'],
    coverage: {
      exclude: [
        'src/app.tsx',
        'src/vite-env.d.ts',
        'dist/**',
        'vitest.config.ts',
      ],
      // 审计 P1-11：覆盖率门禁（CI 构建强制，与 student-h5/teacher-web 对齐）
      // 阈值基于 2026-08 实测（lines 61.77 / branches 68.83 / functions 39.02）留余量
      thresholds: {
        lines: 50,
        branches: 50,
        functions: 30,
        statements: 50,
      },
    },
  },
})
