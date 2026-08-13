/**
 * riskLevel 风险等级工具函数测试（doing/90 P-009 三处收敛）
 * 覆盖：名称/颜色/Key 映射 + 未知等级回退 GREEN。
 */
import { describe, it, expect } from 'vitest'
import { riskLevelName, riskLevelColor, riskLevelKey } from '../utils/riskLevel'

describe('riskLevel 共享常量', () => {
  it('数字等级 → 名称映射（0-3）', () => {
    expect(riskLevelName(0)).toBe('GREEN')
    expect(riskLevelName(1)).toBe('YELLOW')
    expect(riskLevelName(2)).toBe('ORANGE')
    expect(riskLevelName(3)).toBe('RED')
  })

  it('数字等级 → antd Tag 颜色', () => {
    expect(riskLevelColor(0)).toBe('green')
    expect(riskLevelColor(1)).toBe('yellow')
    expect(riskLevelColor(2)).toBe('orange')
    expect(riskLevelColor(3)).toBe('red')
  })

  it('riskLevelKey 与颜色一致', () => {
    expect(riskLevelKey(2)).toBe('orange')
  })

  it('未知/非法等级回退 GREEN', () => {
    expect(riskLevelName(9)).toBe('GREEN')
    expect(riskLevelName(null)).toBe('GREEN')
    expect(riskLevelName(undefined)).toBe('GREEN')
    expect(riskLevelName('abc')).toBe('GREEN')
    expect(riskLevelColor(9)).toBe('green')
  })
})
