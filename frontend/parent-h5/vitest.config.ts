import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
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
        'vitest.config.ts',
        'vite.config.ts',
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
