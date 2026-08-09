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
      exclude: ['src/main.tsx', 'src/vite-env.d.ts', 'dist/**', 'vitest.config.js', 'vite.config.js', 'src/api.ts'],
      thresholds: {
        lines: 70,
        branches: 60,
        functions: 55,
        statements: 70,
      },
    },
  },
});
