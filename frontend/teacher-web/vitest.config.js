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
    },
  },
});
