import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/parent/',
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist'
  },
  // fix-frontend：vitest 配置 + 覆盖率门禁
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
        'vite.config.js',
      ],
      // AUD-022：门禁提升（原 30/20/25/30 形同虚设 → teacher 同规格 + 实测余量）
      // 2026-08-06 实测：lines 73.62 / branches 72.72 / functions 54.76 / statements 73.62
      // functions 偏低系 src/api.ts 被 vi.mock 替换致 v8 统计失真（契约由 apiContract.test 覆盖，与 teacher 同因）
      thresholds: {
        lines: 70,
        branches: 65,
        functions: 50,
        statements: 70,
      },
    },
  },
})
