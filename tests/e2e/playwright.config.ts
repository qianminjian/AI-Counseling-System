import { defineConfig, devices } from '@playwright/test'

const FRONTEND = '../../frontend'

export default defineConfig({
  testDir: './specs',
  fullyParallel: true,
  // DA-04 议决（2026-08-08）：CI 不跑 Playwright E2E（全栈成本高，8983862a 已移除）；
  // 部署现场冒烟走 smoke-test.sh（deploy.sh 后置门禁），以下 CI 分支为本地/未来恢复预留配置
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'teacher-web',
      testMatch: /teacher-web\.spec\.ts/,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'student-h5',
      testMatch: /student-h5\.spec\.ts/,
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'parent-h5',
      testMatch: /parent-h5\.spec\.ts/,
      use: { ...devices['Pixel 5'] },
    },
  ],
  webServer: [
    {
      command: 'npm run dev',
      cwd: `${FRONTEND}/teacher-web`,
      port: 3001,
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
    {
      command: 'VITE_HTTPS=false npm run dev',
      cwd: `${FRONTEND}/student-h5`,
      port: 3000,
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
    {
      command: 'npm run dev',
      cwd: `${FRONTEND}/parent-h5`,
      port: 5174,
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
  ],
})
