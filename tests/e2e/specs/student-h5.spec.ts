import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:3000'

test.describe('student-h5 冒烟测试', () => {
  test('登录页正常渲染（移动端）', async ({ page }) => {
    await page.goto(BASE)
    // 学生端未登录 → LoginPage
    await expect(page.locator('input, button').first()).toBeVisible({ timeout: 10_000 })
    // 页面标题或品牌元素
    const body = page.locator('body')
    await expect(body).not.toBeEmpty()
  })

  test('页面无关键 JS 错误', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (err) => errors.push(err.message))
    await page.goto(BASE)
    await page.waitForLoadState('networkidle')
    const critical = errors.filter((e) => !e.includes('fetch') && !e.includes('network'))
    expect(critical).toHaveLength(0)
  })

  test('家长周报路由可访问（/parent）', async ({ page }) => {
    await page.goto(`${BASE}/parent?token=test`)
    // ParentReport 组件应渲染（即使 token 无效也不白屏）
    await expect(page.locator('body')).not.toBeEmpty()
    await page.waitForLoadState('networkidle')
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
