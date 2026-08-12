/**
 * doing/92 R-005：色板单源一致性守卫
 *
 * teacher-web 图表色板自 shared/theme.ts 导出，其中与 CSS 有对应 token 的
 * 色值（primary/primaryDeep/warning/danger）必须与 src/index.css --ms-* 一致——
 * 否则双轨漂移复发（ECharts canvas 不支持 CSS var()，须真实色值）。
 */
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { themeColors } from '../../../shared/src/theme'

const css = readFileSync(join(process.cwd(), 'src/index.css'), 'utf-8')

/** 取 --ms-* CSS 变量值（trim 后） */
function cssVar(name: string): string | undefined {
  const m = css.match(new RegExp(`--${name}:\\s*([^;]+);`))
  return m ? m[1].trim() : undefined
}

describe('theme.ts 与 index.css token 一致性（doing/92 R-005）', () => {
  it('primary 与 --ms-primary 同值', () => {
    expect(cssVar('ms-primary')).toBe(themeColors.primary)
  })
  it('primaryDeep 与 --ms-primary-strong 同值', () => {
    expect(cssVar('ms-primary-strong')).toBe(themeColors.primaryDeep)
  })
  it('warning 与 --ms-warning 同值', () => {
    expect(cssVar('ms-warning')).toBe(themeColors.warning)
  })
  it('danger 与 --ms-danger 同值', () => {
    expect(cssVar('ms-danger')).toBe(themeColors.danger)
  })
})
