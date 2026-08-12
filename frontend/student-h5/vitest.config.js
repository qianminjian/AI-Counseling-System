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
      // 审计 P1-11：覆盖率门禁（CI 构建强制）
      // 板块12 P1-2：2026-08-12 实测 93.33/89.71/88.41/93.33，阈值 50/35/40/50 → 60/50/55/60
      // （分步提升第一步，余量 >30pct 确保 CI 通过；后续批次对齐 teacher-web 80/75/60/80）
      thresholds: {
        lines: 60,
        branches: 50,
        functions: 55,
        statements: 60,
      },
    },
  },
});
