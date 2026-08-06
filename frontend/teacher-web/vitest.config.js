import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
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
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      exclude: [
        'src/main.tsx',
        'src/vite-env.d.ts',
        'dist/**',
        'vitest.config.js',
        'vite.config.js',
        // api.ts 被所有组件测试 vi.mock 替换（vitest 4 起不再失真、直接计入统计），
        // 其契约已由 src/test/apiContract.test.ts + authFetch 测试单独覆盖，排除避免双重口径
        'src/api.ts',
      ],
      // 审计 P1-11 + ARCH-009 E-2：覆盖率门禁（CI test:coverage 强制）
      // 2026-08 补测后实测：lines 89.99 / branches 82.24 / functions 66.48 / statements 89.99
      // functions 偏低系 api.ts 被 vi.mock 替换致 v8 统计失真（契约由 authFetch/apiContract 测试覆盖），余量 6%
      thresholds: {
        lines: 80,
        branches: 75,
        functions: 60,
        statements: 80,
      },
    },
  },
});
