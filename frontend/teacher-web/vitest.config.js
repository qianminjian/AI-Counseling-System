import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  esbuild: {
    jsx: 'automatic',
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      exclude: [
        'src/main.tsx',
        'src/vite-env.d.ts',
        'dist/**',
        'vitest.config.js',
        'vite.config.js',
      ],
      // 审计 P1-11：覆盖率门禁（CI 构建强制）
      // 阈值基于 2026-08 实测（lines 21.48）留余量——此前 30% 为拍脑袋值从未达标
      thresholds: {
        lines: 20,
        branches: 15,
        functions: 20,
        statements: 20,
      },
    },
  },
});
