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
