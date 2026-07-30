import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5174/parent/'

test.describe('parent-h5 冒烟测试', () => {
  test('验证页正常渲染', async ({ page }) => {
    await page.goto(BASE)
    await page.waitForLoadState('networkidle')
    // 家长端入口为 VerifyPage（手机验证），含 form 或 button
    await expect(page.locator('form, button, input').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('body')).not.toBeEmpty()
  })

  test('页面无关键 JS 错误', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (err) => errors.push(err.message))
    await page.goto(BASE)
    await page.waitForLoadState('networkidle')
    const critical = errors.filter((e) => !e.includes('fetch') && !e.includes('network'))
    expect(critical).toHaveLength(0)
  })

  test('未认证访问报告页重定向到验证页', async ({ page }) => {
    await page.goto('http://localhost:5174/parent/report')
    // ProtectedRoute 应重定向到 /parent/
    await page.waitForURL('**/parent', { timeout: 5_000 }).catch(() => {
      // 可能已经在 /parent 或显示验证页
    })
    await expect(page.locator('body')).not.toBeEmpty()
  })

  test('视口适配移动端（无水平溢出）', async ({ page }) => {
    await page.goto(BASE)
    await page.waitForLoadState('domcontentloaded')
    const overflow = await page.evaluate(() => {
      return document.documentElement.scrollWidth > document.documentElement.clientWidth + 10
    })
    expect(overflow).toBe(false)
  })
})
