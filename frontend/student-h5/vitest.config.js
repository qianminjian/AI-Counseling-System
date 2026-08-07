import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  // DC-005：共享模块位于 root 之外（../shared），Vite fs.allow 放行否则无法加载
  server: {
    fs: {
      allow: ['.', '../shared/src'],
    },
  },
  plugins: [react()],
  esbuild: {
    jsx: 'automatic',
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
        'src/main.tsx',
        'src/global.d.ts',
        'src/vite-env.d.ts',
        'dist/**',
        'vitest.config.js',
        'vite.config.js',
      ],
      // 审计 P1-11：覆盖率门禁（CI 构建强制，2026-08 实测 lines ~85 达标）
      thresholds: {
        lines: 50,
        branches: 35,
        functions: 40,
        statements: 50,
      },
    },
  },
});
