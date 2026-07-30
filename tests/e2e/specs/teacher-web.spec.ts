import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:3001'

test.describe('teacher-web 冒烟测试', () => {
  test('登录页正常渲染', async ({ page }) => {
    await page.goto(BASE)
    // 未登录应展示登录表单
    await expect(page.locator('input#username, input[placeholder*="用户名"]').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('input[type="password"]').first()).toBeVisible()
    await expect(page.locator('button[type="submit"], button:has-text("登录")').first()).toBeVisible()
  })

  test('登录页无关键 JS 错误', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (err) => errors.push(err.message))
    await page.goto(BASE)
    await page.waitForLoadState('networkidle')
    // 允许 API 请求失败（无后端），但不允许渲染崩溃
    const critical = errors.filter((e) => !e.includes('fetch') && !e.includes('network'))
    expect(critical).toHaveLength(0)
  })

  test('空表单提交显示校验提示', async ({ page }) => {
    await page.goto(BASE)
    const submitBtn = page.locator('button[type="submit"], button:has-text("登录")').first()
    await submitBtn.click()
    // antd Form 校验提示
    await expect(page.locator('.ant-form-item-explain, .ant-message').first()).toBeVisible({ timeout: 5_000 })
  })

  test('错误凭据登录失败有反馈', async ({ page }) => {
    await page.goto(BASE)
    await page.locator('input#username, input[placeholder*="用户名"]').first().fill('bad_user')
    await page.locator('input[type="password"]').first().fill('bad_pass')
    const submitBtn = page.locator('button[type="submit"], button:has-text("登录")').first()
    await submitBtn.click()
    // 应显示错误提示（message/notification），而非白屏
    await expect(page.locator('.ant-message, .ant-notification, .ant-alert').first()).toBeVisible({ timeout: 10_000 })
  })
})
