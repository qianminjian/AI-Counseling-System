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
        // 样式文件不可单测（v8 对 scss 计 0 覆盖，拉低全局阈值）
        '**/*.scss',
        'src/app.tsx',
        'src/app.config.ts',
        'src/vite-env.d.ts',
        // Taro CLI 构建配置（config/ 与 babel 配置，非可测业务代码）
        'config/**',
        'babel.config.cjs',
        'dist/**',
        'vitest.config.ts',
      ],
      // doing/73 AC-12（用户指令）：覆盖率门禁提升至 80/80/80/80（四维度均 ≥80%）
      // 阈值基于 2026-08-07 T3 后实测（排除 CLI 配置后 statements 80+ / lines 80+）
      thresholds: {
        lines: 80,
        branches: 80,
        functions: 80,
        statements: 80,
      },
    },
  },
})
