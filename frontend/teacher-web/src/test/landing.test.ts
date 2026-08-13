/**
 * 五角色默认落地页路由测试（F-3，design/35 §3.1）
 * 覆盖：admin → 数据大屏；其余角色/未知/缺失 → 工作台。
 */
import { describe, it, expect } from 'vitest'
import { defaultLandingFor } from '../utils/landing'

describe('defaultLandingFor（角色差异化落地页）', () => {
  it('admin（校领导/管理者）→ bigscreen 数据大屏', () => {
    expect(defaultLandingFor('admin')).toBe('bigscreen')
  })

  it('心理老师 → dashboard 工作台', () => {
    expect(defaultLandingFor('counselor')).toBe('dashboard')
  })

  it('班主任/普通教师 → dashboard', () => {
    expect(defaultLandingFor('head_teacher')).toBe('dashboard')
    expect(defaultLandingFor('teacher')).toBe('dashboard')
  })

  it('未知/缺失角色 → 保守落工作台', () => {
    expect(defaultLandingFor('unknown_role')).toBe('dashboard')
    expect(defaultLandingFor(null)).toBe('dashboard')
    expect(defaultLandingFor(undefined)).toBe('dashboard')
  })
})
